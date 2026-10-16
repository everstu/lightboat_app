package com.lightboat.app.userscript

import android.os.SystemClock
import android.util.Base64
import android.webkit.CookieManager
import com.lightboat.app.DebugLog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScriptNetwork(private val meta: ScriptMetadata, private val userAgent: String) {
    private data class Request(val stopped: AtomicBoolean = AtomicBoolean(false), @Volatile var connection: HttpURLConnection? = null)
    private val requests = ConcurrentHashMap<String, Request>()
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var closed = false

    fun abort(key: String) { requests[key]?.let { it.stopped.set(true); it.connection?.disconnect() } }
    fun abortDocument(doc: String) { requests.keys.filter { it.startsWith("$doc:") }.forEach { abort(it) } }

    fun execute(key: String, pageUrl: String, details: JSONObject, event: (String, JSONObject?, String?) -> Unit) {
        check(!closed)
        require(requests.size < 16) { "同时请求数量不能超过 16" }
        val target = details.getString("target")
        val requestMethod = details.optString("method", "GET").uppercase()
        val started = SystemClock.elapsedRealtime()
        val timeout = details.optLong("timeout", 30000).let { if (it <= 0) 120000 else it.coerceAtMost(120000) }
        DebugLog.event("gm-xhr", "start method=$requestMethod url=${DebugLog.safeUrl(target)} responseType=${details.optString("responseType", "text")}")
        val request = Request()
        require(requests.putIfAbsent(key, request) == null) { "请求 ID 重复" }
        pool.execute task@ {
            var current = target
            var method = requestMethod
            var body: ByteArray? = null
            try {
                require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) { "不支持请求方法 $method" }
                val rawBody = details.opt("data")
                body = when (rawBody) {
                    is String -> rawBody.toByteArray(Charsets.UTF_8)
                    is JSONObject -> Base64.decode(rawBody.getString("base64"), Base64.DEFAULT)
                    else -> null
                }
                require((body?.size ?: 0) <= 1024 * 1024) { "请求正文不能超过 1 MiB" }
                val headers = details.optJSONObject("headers") ?: JSONObject()
                require(headers.length() <= 64) { "请求头过多" }
                event("loadstart", JSONObject().put("readyState", 1).put("status", 0), null)
                repeat(11) { hop ->
                    if (request.stopped.get()) throw IOException("请求已取消")
                    require(meta.permitsConnection(current, pageUrl)) { "@connect 未授权访问 ${URI(current).host}" }
                    val remaining = timeout - (SystemClock.elapsedRealtime() - started)
                    if (remaining <= 0) throw SocketTimeoutException("请求超时")
                    val connection = URI(current).toURL().openConnection() as HttpURLConnection
                    request.connection = connection
                    try {
                        if (request.stopped.get()) throw IOException("请求已取消")
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = remaining.toInt()
                        connection.readTimeout = remaining.toInt()
                        connection.requestMethod = method
                        if (!details.optBoolean("anonymous")) CookieManager.getInstance().getCookie(current)?.let {
                            connection.setRequestProperty("Cookie", it)
                        }
                        val sameInitialOrigin = ScriptMetadata.sameOrigin(current, details.getString("target"))
                        headers.keys().forEach { name ->
                            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "请求头名称无效" }
                            val value = headers.getString(name)
                            require(value.length <= 8192 && '\r' !in value && '\n' !in value) { "请求头内容无效" }
                            val lower = name.lowercase()
                            require(lower !in setOf("host", "content-length", "connection", "transfer-encoding", "cookie", "proxy-authorization")) {
                                "不支持覆盖请求头 $name"
                            }
                            if (lower != "user-agent" && (sameInitialOrigin || lower !in setOf("authorization", "origin", "referer"))) connection.setRequestProperty(name, value)
                        }
                        // The profile owns request identity, including script headers and redirects.
                        connection.setRequestProperty("User-Agent", userAgent)
                        if (body != null && method !in setOf("GET", "HEAD")) {
                            connection.doOutput = true
                            val requestBytes = body ?: ByteArray(0)
                            connection.outputStream.use { it.write(requestBytes) }
                        }
                        val status = connection.responseCode
                        if (!details.optBoolean("anonymous")) connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }
                            .values.flatten().forEach { CookieManager.getInstance().setCookie(current, it) }
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            require(hop < 10) { "重定向次数过多" }
                            val location = connection.getHeaderField("Location") ?: error("重定向目标为空")
                            current = URI(current).resolve(location).toString()
                            DebugLog.event("gm-xhr", "redirect status=$status to=${DebugLog.safeUrl(current)}")
                            if (status == 303 || ((status == 301 || status == 302) && method == "POST")) { method = "GET"; body = null }
                        } else {
                            DebugLog.event("gm-xhr", "response status=$status url=${DebugLog.safeUrl(current)} type=${connection.contentType.orEmpty().substringBefore(';').take(80)}")
                            val rawHeaders = connection.headerFields.filterKeys { it != null }.entries.joinToString("\r\n") { (name, values) ->
                                values.joinToString("\r\n") { "$name: $it" }
                            }
                            event("readystatechange", JSONObject().put("readyState", 2).put("status", status).put("responseHeaders", rawHeaders), null)
                            val output = ByteArrayOutputStream()
                            val input = if (status >= 400) connection.errorStream else connection.inputStream
                            var lastProgress = 0L
                            input?.use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (request.stopped.get()) throw IOException("请求已取消")
                                    val left = timeout - (SystemClock.elapsedRealtime() - started)
                                    if (left <= 0) throw SocketTimeoutException("请求超时")
                                    connection.readTimeout = left.toInt()
                                    val count = stream.read(buffer)
                                    if (count == -1) break
                                    require(output.size() + count <= 4 * 1024 * 1024) { "GM 请求响应不能超过 4 MiB" }
                                    output.write(buffer, 0, count)
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastProgress >= 150) {
                                        event("progress", JSONObject().put("loaded", output.size()).put("total", maxOf(0, connection.contentLengthLong))
                                            .put("lengthComputable", connection.contentLengthLong >= 0).put("readyState", 3).put("status", status), null)
                                        lastProgress = now
                                    }
                                }
                            }
                            val bytes = output.toByteArray()
                            val contentType = connection.contentType.orEmpty()
                            val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                                .find(contentType)?.groupValues?.get(1)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
                            val binary = details.optString("responseType") in setOf("arraybuffer", "blob")
                            val result = JSONObject().put("status", status).put("statusText", connection.responseMessage.orEmpty())
                                .put("finalUrl", current).put("responseHeaders", rawHeaders).put("mime", contentType.substringBefore(';'))
                                .put("responseText", if (binary) "" else bytes.toString(charset))
                            if (binary) result.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            event("load", result, null)
                            DebugLog.event("gm-xhr", "load status=$status bytes=${bytes.size} final=${DebugLog.safeUrl(current)}")
                            return@task
                        }
                    } finally { connection.disconnect(); request.connection = null }
                }
            } catch (error: Exception) {
                val kind = when { request.stopped.get() -> "abort"; error is SocketTimeoutException -> "timeout"; else -> "error" }
                DebugLog.event("gm-xhr", "$kind url=${DebugLog.safeUrl(current)} error=${error.message.orEmpty().take(240)}")
                event(kind, null, error.message ?: "网络请求失败")
            } finally { requests.remove(key) }
        }
    }

    fun close() { closed = true; requests.keys.forEach { abort(it) }; pool.shutdownNow() }
}

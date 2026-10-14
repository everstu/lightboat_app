package com.lightboat.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Used only by the native media proxy. Chromium fetch/XHR are never replayed here. */
internal class MediaRequestClient(
    private val userAgent: String,
    private val cookies: (String) -> String? = { null },
    private val saveCookie: (String, String) -> Unit = { _, _ -> },
) {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val sequence = AtomicLong()
    @Volatile private var closed = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val current = chain.request()
            val url = current.url.toString()
            val cookie = cookies(url)
            val request = current.newBuilder().header("User-Agent", userAgent)
                .removeHeader("X-Requested-With").removeHeader("Cookie")
            cookie?.let { request.header("Cookie", it) }
            DebugLog.event("media-upstream",
                "${current.method} ${DebugLog.safeUrl(url)} ua=${userAgent.take(300)} hasCookie=${!cookie.isNullOrBlank()} range=${current.header("Range").orEmpty()}")
            val response = chain.proceed(request.build())
            response.headers("Set-Cookie").forEach { saveCookie(url, it) }
            DebugLog.event("media-upstream",
                "status=${response.code} type=${response.header("Content-Type").orEmpty()} length=${response.header("Content-Length").orEmpty()} url=${DebugLog.safeUrl(url)}")
            response
        }.build()

    fun execute(url: String, method: String, headers: Map<String, String>): Response {
        check(!closed) { "媒体会话已结束" }
        val request = Request.Builder().url(url).method(method, null)
        headers.forEach { (name, value) ->
            if (name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding",
                    "accept-encoding", "cookie", "user-agent", "x-requested-with", "proxy-authorization")) {
                request.header(name, value)
            }
        }
        request.header("User-Agent", userAgent)
        val call = client.newCall(request.build())
        val id = sequence.incrementAndGet()
        calls.add(call)
        if (closed) call.cancel()
        return try {
            val response = call.execute()
            val body = response.body ?: return response.also { calls.remove(call) }
            val source = object : ForwardingSource(body.source()) {
                private var bytes = 0L
                private var first = true
                private var ended = false
                private var released = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    return try {
                        val count = super.read(sink, byteCount)
                        if (count > 0) {
                            bytes += count
                            if (first) {
                                first = false
                                val sample = Buffer()
                                sink.copyTo(sample, sink.size - count, minOf(count, 32L))
                                DebugLog.event("media-body",
                                    "request=$id category=${category(sample.readByteArray())} url=${DebugLog.safeUrl(url)}")
                            }
                        } else if (count == -1L) ended = true
                        count
                    } catch (error: Exception) {
                        DebugLog.event("media-body", "request=$id read failed bytes=$bytes ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                        throw error
                    }
                }
                override fun close() {
                    if (released) return
                    released = true
                    try { super.close() } finally {
                        calls.remove(call)
                        DebugLog.event("media-body", "request=$id closed bytes=$bytes complete=${ended || bytes == body.contentLength()} url=${DebugLog.safeUrl(url)}")
                    }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Exception) {
            calls.remove(call)
            DebugLog.event("media-upstream", "request=$id failed ${error.javaClass.simpleName}: ${error.message.orEmpty()} url=${DebugLog.safeUrl(url)}")
            throw error
        }
    }

    fun close() {
        closed = true
        calls.forEach { it.cancel() }
        calls.clear()
        client.connectionPool.evictAll()
    }

    private fun category(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.US_ASCII).trimStart().lowercase()
        return when {
            text.startsWith("#extm3u") -> "HLS"
            text.startsWith("<!doctype") || text.startsWith("<html") -> "HTML"
            text.startsWith("{") || text.startsWith("[") -> "JSON-or-text"
            bytes.size >= 8 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) in setOf("ftyp", "styp", "moof") -> "MP4"
            bytes.firstOrNull()?.toInt()?.and(0xff) == 0x47 -> "MPEG-TS"
            else -> "binary-or-encrypted"
        }
    }
}

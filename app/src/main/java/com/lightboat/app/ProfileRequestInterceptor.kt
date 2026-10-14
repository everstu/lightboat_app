package com.lightboat.app

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Replays mismatched-UA and standard media GET/HEAD requests with the selected profile identity. */
class ProfileRequestInterceptor(private val userAgent: String, private val network: ProfileRequestClient) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.scheme !in setOf("http", "https") || request.method !in setOf("GET", "HEAD")) return null
        val actual = request.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val media = DebugLog.isMediaRequest(request.url.toString(), request.requestHeaders)
        if ((actual == userAgent && !media) || request.url.host == "127.0.0.1") return null
        if (DebugLog.shouldTraceUrl(request.url.toString())) {
            DebugLog.event("native-replay", "${request.method} ${DebugLog.safeUrl(request.url.toString())} headers=${DebugLog.headersPresent(request.requestHeaders)}")
        }
        return try {
            // A native replay cannot consume Chromium's cached body after a
            // 304. Force a complete body while preserving Range/If-Range.
            val response = network.execute(
                request.url.toString(), request.method, request.requestHeaders, forceFresh = true
            )
            if (DebugLog.shouldTraceUrl(request.url.toString())) {
                DebugLog.event("native-replay", "response status=${response.code} type=${response.header("Content-Type").orEmpty().take(80)} url=${DebugLog.safeUrl(request.url.toString())}")
            }
            val mime = response.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
            val encoding = response.body?.contentType()?.charset()?.name() ?: "UTF-8"
            val blockedHeaders = mutableSetOf("set-cookie", "content-encoding", "transfer-encoding", "connection")
            if (!media) blockedHeaders += setOf("cache-control", "expires", "etag", "last-modified")
            val headers = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                .filterKeys { it.lowercase() !in blockedHeaders }
            WebResourceResponse(mime, encoding, response.code, response.message.ifBlank { "Response" }, headers, network.stream(response))
        } catch (_: Exception) {
            DebugLog.event("native-replay", "failed ${request.method} ${DebugLog.safeUrl(request.url.toString())}")
            // Do not leak a second, differently identified request by falling back.
            WebResourceResponse("text/plain", "UTF-8", 502, "Request failed", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }
}

package com.lightboat.app

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Chromium keeps its own cache/body handling unless a GET/HEAD has the wrong UA. */
class ProfileRequestInterceptor(private val userAgent: String, private val network: ProfileRequestClient) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.scheme !in setOf("http", "https") || request.method !in setOf("GET", "HEAD")) return null
        val actual = request.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        if (actual == userAgent || request.url.host == "127.0.0.1") return null
        return try {
            val response = network.execute(request.url.toString(), request.method, request.requestHeaders)
            val mime = response.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
            val encoding = response.body?.contentType()?.charset()?.name() ?: "UTF-8"
            val headers = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                .filterKeys { it.lowercase() !in setOf("set-cookie", "content-encoding", "transfer-encoding", "connection") }
            WebResourceResponse(mime, encoding, response.code, response.message.ifBlank { "Response" }, headers, network.stream(response))
        } catch (_: Exception) {
            // Do not leak a second, differently identified request by falling back.
            WebResourceResponse("text/plain", "UTF-8", 502, "Request failed", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }
}

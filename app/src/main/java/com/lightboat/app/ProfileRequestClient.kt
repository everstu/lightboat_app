package com.lightboat.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Immutable identity for one browsing session, including every redirect hop. */
class ProfileRequestClient(
    private val userAgent: String,
    base: OkHttpClient = OkHttpClient(),
    private val cookies: (String) -> String? = { null },
    private val saveCookie: (String, String) -> Unit = { _, _ -> }
) {
    private object ForceFreshTag
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    private val client = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url.toString()
            val forceFresh = chain.request().tag(ForceFreshTag::class.java) != null
            val request = chain.request().newBuilder().header("User-Agent", userAgent)
                .removeHeader("X-Requested-With").removeHeader("Cookie")
            if (forceFresh) {
                request.removeHeader("If-None-Match").removeHeader("If-Modified-Since")
                    .header("Cache-Control", "no-cache")
            }
            cookies(url)?.let { request.header("Cookie", it) }
            val response = chain.proceed(request.build())
            response.headers("Set-Cookie").forEach { saveCookie(url, it) }
            // Do not reuse a response obtained under another configured identity.
            response.newBuilder().addHeader("Vary", "User-Agent").build()
        }.build()

    fun execute(url: String, method: String, headers: Map<String, String>, forceFresh: Boolean = false): Response {
        check(!closed) { "配置请求已结束" }
        val request = Request.Builder().url(url).method(method, null)
        headers.forEach { (name, value) ->
            if (name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding",
                    "accept-encoding", "cookie", "user-agent", "x-requested-with", "proxy-authorization")) {
                request.header(name, value)
            }
        }
        if (forceFresh) request.tag(ForceFreshTag::class.java, ForceFreshTag)
        request.header("User-Agent", userAgent)
        cookies(url)?.let { request.header("Cookie", it) }
        val call = client.newCall(request.build())
        calls.add(call)
        if (closed) call.cancel()
        return try {
            val response = call.execute()
            val body = response.body ?: return response.also { calls.remove(call) }
            val source = object : ForwardingSource(body.source()) {
                override fun close() { try { super.close() } finally { calls.remove(call) } }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Exception) { calls.remove(call); throw error }
    }

    fun stream(response: Response): InputStream = response.body!!.byteStream()

    fun release(response: Response) {
        response.close()
    }

    fun close() {
        closed = true
        calls.forEach { it.cancel() }
        calls.clear()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        DebugLog.event("network", "profile transport closed")
    }
}

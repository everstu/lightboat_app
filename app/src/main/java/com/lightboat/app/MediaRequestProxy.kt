package com.lightboat.app

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Only loopback sees the native player's UA. Upstream always sees the profile's UA. */
class MediaRequestProxy(private val network: ProfileRequestClient) : NanoHTTPD("127.0.0.1", 0) {
    val token: String = UUID.randomUUID().toString()
    val baseUrl get() = "http://127.0.0.1:$listeningPort/$token/media"
    private val workers = Executors.newFixedThreadPool(6)
    private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    init {
        setAsyncRunner(object : AsyncRunner {
            override fun exec(handler: ClientHandler) {
                if (clients.size >= 24) { handler.close(); return }
                clients.add(handler)
                try { workers.execute(handler) } catch (_: RejectedExecutionException) { handler.close(); clients.remove(handler) }
            }
            override fun closed(handler: ClientHandler) { clients.remove(handler) }
            override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear() }
        })
    }

    fun start() = start(SOCKET_READ_TIMEOUT, true)
    fun close() { stop(); workers.shutdownNow() }

    fun url(remote: String, referer: String): String = "$baseUrl?url=${encode(remote)}&ref=${encode(referer)}"

    fun rewritePlaylist(text: String, finalUrl: String, referer: String): String =
        text.lineSequence().joinToString("\n") { line ->
            if (line.startsWith('#')) uriAttribute.replace(line) { match ->
                "URI=\"${routeReference(finalUrl, match.groupValues[1], referer)}\""
            } else if (line.isBlank()) line else routeReference(finalUrl, line.trim(), referer)
        }

    private fun routeReference(base: String, relative: String, referer: String): String {
        val resolved = URI(base).resolve(relative).toString()
        return if (URI(resolved).scheme in setOf("http", "https")) url(resolved, referer) else relative
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/$token/media") return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        if (session.method !in setOf(Method.GET, Method.HEAD)) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET/HEAD only")
        }
        val remote = session.parameters["url"]?.singleOrNull().orEmpty()
        val referer = session.parameters["ref"]?.singleOrNull().orEmpty()
        val uri = runCatching { URI(remote) }.getOrNull()
        val host = uri?.host?.lowercase()?.removeSurrounding("[", "]")
        if (remote.length > 8192 || uri?.scheme !in setOf("http", "https") || host == null || uri?.rawUserInfo != null ||
            host == "localhost" || isLoopback(host)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid media URL")
        }
        val remoteUri = requireNotNull(uri)
        return try {
            val headers = linkedMapOf<String, String>()
            // Never forward loopback Host, cookies, Origin, credentials, or the player's UA.
            listOf("range", "if-range", "if-none-match", "if-modified-since", "accept").forEach { name ->
                session.headers[name]?.let { headers[name] = it }
            }
            if (referer.length <= 8192 && runCatching { URI(referer).scheme in setOf("http", "https") }.getOrDefault(false)) {
                headers["Referer"] = referer
            }
            session.headers["origin"]?.takeIf { it != "null" && !it.startsWith("http://127.0.0.1:") }?.let { headers["Origin"] = it }
            val upstream = network.execute(remote, session.method.name, headers)
            val body = upstream.body ?: run { network.release(upstream); error("Empty response") }
            val mime = upstream.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
            val playlist = upstream.isSuccessful && session.method == Method.GET && (remoteUri.path.endsWith(".m3u8", true) ||
                mime.lowercase() in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl"))
            val status = object : Response.IStatus {
                override fun getRequestStatus() = upstream.code
                override fun getDescription() = "${upstream.code} ${upstream.message.ifBlank { "Response" }}"
            }
            val result: Response
            if (playlist) {
                try {
                    val source = body.source()
                    source.request(2L * 1024 * 1024 + 1)
                    require(source.buffer.size <= 2L * 1024 * 1024) { "Playlist too large" }
                    val text = source.readUtf8()
                    require(text.trimStart().startsWith("#EXTM3U")) { "Invalid HLS playlist" }
                    val bytes = rewritePlaylist(text, upstream.request.url.toString(), referer).toByteArray(Charsets.UTF_8)
                    result = newFixedLengthResponse(status, "application/vnd.apple.mpegurl", ByteArrayInputStream(bytes), bytes.size.toLong())
                } finally { network.release(upstream) }
            } else {
                val stream = network.stream(upstream)
                result = if (body.contentLength() >= 0) newFixedLengthResponse(status, mime, stream, body.contentLength())
                    else newChunkedResponse(status, mime, stream)
            }
            upstream.headers.forEach { (name, value) ->
                if (name.lowercase() !in setOf("content-length", "content-type", "content-encoding", "transfer-encoding", "connection",
                        "set-cookie", "location", "content-security-policy", "etag", "last-modified",
                        "access-control-allow-origin", "access-control-allow-credentials") &&
                    (!playlist || name.lowercase() !in setOf("cache-control", "expires"))) result.addHeader(name, value)
            }
            result.addHeader("Access-Control-Allow-Origin", "*")
            // Rewritten playlists belong only to this server/session; other media can
            // retain upstream cache headers and also benefits from the upstream cache.
            if (playlist) result.addHeader("Cache-Control", "no-store")
            result
        } catch (_: Exception) {
            // Fail closed: never retry the original URL with the system media UA.
            newFixedLengthResponse(object : Response.IStatus {
                override fun getRequestStatus() = 502
                override fun getDescription() = "502 Bad Gateway"
            }, "text/plain", "Media request failed")
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun isLoopback(host: String): Boolean = host == "::1" || host.startsWith("127.") ||
        runCatching { java.net.InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
}

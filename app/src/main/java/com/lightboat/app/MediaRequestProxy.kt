package com.lightboat.app

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Only loopback sees the native player's UA. Upstream always sees the profile's UA. */
internal class MediaRequestProxy(
    private val network: ProfileRequestClient,
    private val allowLoopbackUpstreamForTests: Boolean = false
) : NanoHTTPD("127.0.0.1", 0) {
    val token: String = UUID.randomUUID().toString()
    val baseUrl get() = "http://127.0.0.1:$listeningPort/$token/media"
    // A media client can keep a segment/playlist connection open while the
    // player asks for the next one. Do not queue the next request behind six
    // occupied streaming workers; the client cap bounds this pool at 24.
    private val workers = Executors.newCachedThreadPool()
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

    override fun start() = start(SOCKET_READ_TIMEOUT, true)
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
            !allowLoopbackUpstreamForTests && (host == "localhost" || isLoopback(host))) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid media URL")
        }
        val remoteUri = requireNotNull(uri)
        return try {
            val headers = linkedMapOf<String, String>()
            // Never forward loopback Host, cookies, or the player's UA. Keep
            // validators and authorization used by signed media/CDN sessions.
            listOf("range", "if-range", "if-none-match", "if-modified-since", "accept",
                "accept-language", "authorization").forEach { name ->
                session.headers[name]?.let { headers[name] = it }
            }
            if (referer.length <= 8192 && runCatching { URI(referer).scheme in setOf("http", "https") }.getOrDefault(false)) {
                headers["Referer"] = referer
            }
            session.headers["origin"]?.takeIf { it != "null" && !it.startsWith("http://127.0.0.1:") }?.let { headers["Origin"] = it }
            val upstream = network.execute(remote, session.method.name, headers)
            val status = object : Response.IStatus {
                override fun getRequestStatus() = upstream.code
                override fun getDescription() = "${upstream.code} ${upstream.message.ifBlank { "Response" }}"
            }
            val mime = upstream.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
            if (session.method == Method.HEAD) {
                val length = upstream.header("Content-Length")?.toLongOrNull() ?: 0L
                val result = newFixedLengthResponse(status, mime, ByteArrayInputStream(ByteArray(0)), length)
                copyHeaders(upstream, result, playlist = false)
                result.addHeader("Access-Control-Allow-Origin", "*")
                network.release(upstream)
                return result
            }
            val body = upstream.body ?: run { network.release(upstream); error("Empty response") }
            val playlist = upstream.isSuccessful && (remoteUri.path.endsWith(".m3u8", true) ||
                mime.lowercase() in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl"))
            val result: Response
            if (playlist) {
                try {
                    val source = body.source()
                    val firstLine = source.readUtf8Line() ?: error("Empty HLS playlist")
                    require(firstLine.trimStart().startsWith("#EXTM3U")) { "Invalid HLS playlist" }
                    val stream = PlaylistRewriteStream(
                        source,
                        firstLine,
                        { line -> rewritePlaylistLine(line, upstream.request.url.toString(), referer) },
                        { network.release(upstream) },
                    )
                    result = newChunkedResponse(status, "application/vnd.apple.mpegurl", stream)
                } catch (error: Exception) {
                    network.release(upstream)
                    throw error
                }
            } else {
                val stream = CloseOnCloseInputStream(network.stream(upstream)) { network.release(upstream) }
                result = if (body.contentLength() >= 0) newFixedLengthResponse(status, mime, stream, body.contentLength())
                    else newChunkedResponse(status, mime, stream)
            }
            copyHeaders(upstream, result, playlist)
            result.addHeader("Access-Control-Allow-Origin", "*")
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

    private fun rewritePlaylistLine(line: String, finalUrl: String, referer: String): String =
        if (line.startsWith('#')) uriAttribute.replace(line) { match ->
            "URI=\"${routeReference(finalUrl, match.groupValues[1], referer)}\""
        } else if (line.isBlank()) line else routeReference(finalUrl, line.trim(), referer)

    private fun copyHeaders(upstream: okhttp3.Response, result: Response, playlist: Boolean) {
        val blocked = mutableSetOf("content-length", "content-type", "content-encoding", "transfer-encoding", "connection",
            "set-cookie", "location", "content-security-policy", "access-control-allow-origin", "access-control-allow-credentials")
        if (playlist) blocked += setOf("etag", "last-modified", "cache-control", "expires", "pragma")
        upstream.headers.forEach { (name, value) -> if (name.lowercase() !in blocked) result.addHeader(name, value) }
    }

    private fun isLoopback(host: String): Boolean = host == "::1" || host.startsWith("127.") ||
        runCatching { java.net.InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
}

private const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024

private class CloseOnCloseInputStream(
    delegate: InputStream,
    private val onClose: () -> Unit,
) : FilterInputStream(delegate) {
    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        try { super.close() } finally { onClose() }
    }
}

/** Rewrites HLS references line-by-line so live/chunked playlists do not wait for EOF. */
internal class PlaylistRewriteStream(
    private val source: okio.BufferedSource,
    firstLine: String,
    private val rewrite: (String) -> String,
    private val onClose: () -> Unit,
) : InputStream() {
    private var pending = (rewrite(firstLine) + "\n").toByteArray(Charsets.UTF_8)
    private var offset = 0
    private var total = pending.size.toLong()
    private var closed = false

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) == -1) -1 else buffer[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "Invalid read range" }
        if (length == 0) return 0
        if (closed) return -1
        if (this.offset >= pending.size && !readNextLine()) return -1
        val count = minOf(length, pending.size - this.offset)
        pending.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        return count
    }

    private fun readNextLine(): Boolean {
        val line = source.readUtf8Line() ?: run { close(); return false }
        val bytes = (rewrite(line) + "\n").toByteArray(Charsets.UTF_8)
        total += bytes.size
        if (total > MAX_PLAYLIST_BYTES) {
            close()
            error("Playlist too large")
        }
        pending = bytes
        offset = 0
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        try { source.close() } finally { onClose() }
    }
}

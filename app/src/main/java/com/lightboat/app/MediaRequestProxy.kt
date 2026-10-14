package com.lightboat.app

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okio.BufferedSource

/** Proxy for direct media element sources only; it never intercepts browser fetch/XHR. */
internal class MediaRequestProxy(
    private val network: MediaRequestClient,
    private val allowLoopbackUpstreamForTests: Boolean = false,
) : NanoHTTPD("127.0.0.1", 0) {
    val token: String = UUID.randomUUID().toString()
    val baseUrl get() = "http://127.0.0.1:$listeningPort/$token/media"
    private val workers = ThreadPoolExecutor(0, 24, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>())
    private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private val uriAttribute = Regex("URI=\"([^\"]+)\"")
    @Volatile private var closed = false

    init {
        setAsyncRunner(object : AsyncRunner {
            override fun exec(handler: ClientHandler) {
                if (closed || clients.size >= 24) { handler.close(); return }
                clients.add(handler)
                try { workers.execute(handler) } catch (_: RejectedExecutionException) {
                    clients.remove(handler)
                    handler.close()
                }
            }
            override fun closed(handler: ClientHandler) { clients.remove(handler) }
            override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear() }
        })
    }

    override fun start() = start(SOCKET_READ_TIMEOUT, true)
    fun close() { closed = true; stop(); workers.shutdownNow() }
    fun url(remote: String, referer: String): String = "$baseUrl?url=${encode(remote)}&ref=${encode(referer)}"

    private fun rewriteLine(line: String, finalUrl: String, referer: String): String =
        if (line.startsWith('#')) uriAttribute.replace(line) { match ->
            "URI=\"${routeReference(finalUrl, match.groupValues[1], referer)}\""
        } else if (line.isBlank()) line else routeReference(finalUrl, line.trim(), referer)

    private fun routeReference(base: String, relative: String, referer: String): String {
        val resolved = URI(base).resolve(relative).toString()
        return if (URI(resolved).scheme in setOf("http", "https")) url(resolved, referer) else relative
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/$token/media" || closed) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Media session ended")
        if (session.method == Method.OPTIONS) return cors(session, newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        if (session.method !in setOf(Method.GET, Method.HEAD)) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET/HEAD only")
        }
        val remote = session.parameters["url"]?.singleOrNull().orEmpty()
        val referer = session.parameters["ref"]?.singleOrNull().orEmpty()
        val uri = runCatching { URI(remote) }.getOrNull()
        val host = uri?.host?.lowercase()?.removeSurrounding("[", "]")
        if (remote.length > 8192 || uri?.scheme !in setOf("http", "https") || host == null || uri?.rawUserInfo != null ||
            !allowLoopbackUpstreamForTests && isLoopback(host)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid media URL")
        }
        val remoteUri = requireNotNull(uri)
        return try {
            val headers = linkedMapOf<String, String>()
            listOf("range", "if-range", "if-none-match", "if-modified-since", "accept", "accept-language", "authorization").forEach {
                session.headers[it]?.let { value -> headers[it] = value }
            }
            // Rewritten manifests are a new representation: upstream byte
            // offsets and validators cannot describe the downstream text.
            if (remoteUri.path.orEmpty().endsWith(".m3u8", true)) {
                stripManifestValidators(headers)
            }
            if (referer.length <= 8192 && runCatching { URI(referer).scheme in setOf("http", "https") }.getOrDefault(false)) {
                headers["Referer"] = referer
            }
            session.headers["origin"]?.takeIf { it != "null" && !it.startsWith("http://127.0.0.1:") }?.let { headers["Origin"] = it }
            DebugLog.event("media-proxy", "${session.method.name} playerUA=${session.headers["user-agent"].orEmpty().take(300)} url=${DebugLog.safeUrl(remote)}")
            val initial = network.execute(remote, session.method.name, headers)
            val upstream = if (isPlaylist(initial, remoteUri) && initial.code in setOf(206, 304)) {
                // Extensionless/redirected HLS can only be recognized after
                // response headers. Retry once for a complete manifest body.
                initial.close()
                stripManifestValidators(headers)
                DebugLog.event("media-manifest", "retry full manifest url=${DebugLog.safeUrl(remote)}")
                network.execute(remote, session.method.name, headers)
            } else initial
            try {
                val status = object : Response.IStatus {
                    override fun getRequestStatus() = upstream.code
                    override fun getDescription() = "${upstream.code} ${upstream.message.ifBlank { "Response" }}"
                }
                val mime = upstream.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
                val body = upstream.body
                val manifestResource = isPlaylist(upstream, remoteUri)
                val manifest = session.method == Method.GET && manifestResource
                if (manifestResource) require(upstream.code !in setOf(206, 304)) { "Upstream did not return a complete HLS manifest" }
                val playlist = upstream.isSuccessful && manifest
                val result = when {
                    session.method == Method.HEAD -> {
                        val empty = ByteArrayInputStream(ByteArray(0))
                        // A rewritten manifest has no known length until its
                        // GET body is transformed. Do not advertise upstream offsets.
                        val head = if (manifestResource) newChunkedResponse(status, mime, empty)
                            else newFixedLengthResponse(status, mime, empty,
                                upstream.header("Content-Length")?.toLongOrNull()?.coerceAtLeast(0) ?: 0L)
                        upstream.close()
                        head
                    }
                    playlist -> {
                        val source = requireNotNull(body) { "Empty playlist response" }.source()
                        val first = try { source.readUtf8LineStrict(512).removePrefix("\uFEFF") }
                            catch (_: java.io.EOFException) { throw java.io.IOException("Missing or invalid HLS header") }
                        require(first.trim() == "#EXTM3U") { "Upstream did not return an HLS playlist (type=$mime)" }
                        DebugLog.event("media-manifest", "HLS accepted url=${DebugLog.safeUrl(upstream.request.url.toString())}")
                        val stream = HlsPlaylistStream(source, first,
                            { line -> rewriteLine(line, upstream.request.url.toString(), referer) }, { upstream.close() })
                        newChunkedResponse(Response.Status.OK, "application/vnd.apple.mpegurl", stream)
                    }
                    else -> {
                        val mediaBody = requireNotNull(body) { "Empty media response" }
                        val stream = mediaBody.byteStream()
                        if (mediaBody.contentLength() >= 0) newFixedLengthResponse(status, mime, stream, mediaBody.contentLength())
                        else newChunkedResponse(status, mime, stream)
                    }
                }
                val blocked = mutableSetOf("content-length", "content-type", "transfer-encoding", "connection",
                    "set-cookie", "location", "content-security-policy", "access-control-allow-origin", "access-control-allow-credentials")
                // OkHttp removes Content-Encoding when it decompresses. Any
                // remaining encoding belongs to the unchanged media bytes.
                if (manifestResource) blocked += setOf("etag", "last-modified", "cache-control", "expires", "pragma", "content-range", "content-encoding")
                upstream.headers.forEach { (name, value) -> if (name.lowercase() !in blocked) result.addHeader(name, value) }
                if (manifestResource) result.addHeader("Cache-Control", "no-store")
                cors(session, result)
            } catch (error: Exception) { upstream.close(); throw error }
        } catch (error: Exception) {
            DebugLog.event("media-error", "${error.javaClass.simpleName}: ${error.message.orEmpty()} url=${DebugLog.safeUrl(remote)}")
            cors(session, newFixedLengthResponse(object : Response.IStatus {
                override fun getRequestStatus() = 502
                override fun getDescription() = "502 Media upstream failed"
            }, "text/plain", "Media upstream failed"))
        }
    }

    private fun cors(session: IHTTPSession, result: Response): Response {
        val origin = session.headers["origin"]?.takeIf { it != "null" }
        result.addHeader("Access-Control-Allow-Origin", origin ?: "*")
        if (origin != null) result.addHeader("Access-Control-Allow-Credentials", "true")
        result.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        result.addHeader("Access-Control-Allow-Headers", "Range, If-Range, If-None-Match, If-Modified-Since, Authorization")
        result.addHeader("Access-Control-Allow-Private-Network", "true")
        result.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
        // Do not occupy a streaming worker waiting for an idle loopback keepalive.
        result.addHeader("Connection", "close")
        return result
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun stripManifestValidators(headers: MutableMap<String, String>) {
        listOf("range", "if-range", "if-none-match", "if-modified-since").forEach { headers.remove(it) }
    }

    private fun isPlaylist(response: okhttp3.Response, uri: URI): Boolean =
        uri.path.orEmpty().endsWith(".m3u8", true) || response.request.url.encodedPath.endsWith(".m3u8", true) ||
            response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase() in
                setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")

    private fun isLoopback(host: String): Boolean = host == "localhost" || host == "::1" || host.startsWith("127.") ||
        runCatching { java.net.InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
}

/** Emits rewritten complete HLS lines promptly instead of buffering the entire response. */
internal class HlsPlaylistStream(
    private val source: BufferedSource,
    first: String,
    private val rewrite: (String) -> String,
    private val release: () -> Unit,
) : InputStream() {
    private val limit = 2L * 1024 * 1024
    private var consumed = first.toByteArray(Charsets.UTF_8).size.toLong() + 1
    private var pending = (rewrite(first) + "\n").toByteArray(Charsets.UTF_8)
    private var offset = 0
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        if (closed) return -1
        if (this.offset == pending.size) {
            try {
                if (source.exhausted()) { close(); return -1 }
                val remaining = limit - consumed
                require(remaining >= 0) { "HLS playlist exceeds 2 MiB" }
                val line = try { source.readUtf8LineStrict(remaining) } catch (_: java.io.EOFException) {
                    if (source.buffer.size > remaining) throw java.io.IOException("HLS line exceeds remaining manifest limit")
                    source.readUtf8()
                }
                consumed += line.toByteArray(Charsets.UTF_8).size + 1
                require(consumed <= limit) { "HLS playlist exceeds 2 MiB" }
                pending = (rewrite(line) + "\n").toByteArray(Charsets.UTF_8)
                this.offset = 0
            } catch (error: Exception) {
                DebugLog.event("media-manifest", "read failed ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                close()
                throw error
            }
        }
        val count = minOf(length, pending.size - this.offset)
        pending.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        return count
    }

    override fun close() {
        if (closed) return
        closed = true
        try { source.close() } finally { release() }
    }
}

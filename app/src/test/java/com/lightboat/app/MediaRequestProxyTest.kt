package com.lightboat.app

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import okio.Buffer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class MediaRequestProxyTest {
    @Test fun hlsKeyWithKeynoteMimeRetainsItsSixteenBytesAndRange() {
        MockWebServer().use { server ->
            val key = ByteArray(16) { (it * 17).toByte() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/master.m3u8") {
                    MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/api/app/vid/sec\"\n#EXTINF:5,\nsegment.ts\n")
                } else MockResponse().setResponseCode(206)
                    .setHeader("Content-Type", "application/vnd.apple.keynote")
                    .setHeader("Content-Range", "bytes 0-15/16").setBody(Buffer().write(key))
            }
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                val browser = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
                val keyUrl = proxy.url(server.url("/api/app/vid/sec").toString(), "https://page.example/watch")
                val manifest = browser.newCall(Request.Builder()
                    .url(proxy.url(server.url("/master.m3u8").toString(), "https://page.example/watch"))
                    .build()).execute().use { assertEquals(200, it.code); it.body!!.string() }
                assertTrue(manifest.contains("URI=\"$keyUrl\""))
                browser.newCall(Request.Builder().url(keyUrl).header("Range", "bytes=0-").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("application/octet-stream", it.header("Content-Type"))
                    assertEquals("bytes 0-15/16", it.header("Content-Range"))
                    assertArrayEquals(key, it.body!!.bytes())
                }
                assertNull(server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
                assertEquals("bytes=0-", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
            } finally { proxy.close(); network.close() }
        }
    }

    @Test fun encryptedHlsSegmentsWithQtMimeRetainEveryByte() {
        MockWebServer().use { server ->
            val encrypted = ByteArray(512) { (it * 31 + 193).toByte() }
            val types = listOf("text/vnd.qt.linguist; charset=utf-8", "text/vnd.trolltech.linguist; charset=utf-8")
            types.forEach { type -> server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", type).setHeader("Content-Range", "bytes 0-511/512")
                .setBody(Buffer().write(encrypted))) }
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                val browser = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
                types.forEach {
                    browser.newCall(Request.Builder()
                        .url(proxy.url(server.url("/segment.ts?md=signed").toString(), "https://page.example/watch"))
                        .header("Range", "bytes=0-").build()).execute().use {
                        assertEquals(206, it.code)
                        assertEquals("application/octet-stream", it.header("Content-Type"))
                        assertEquals("bytes 0-511/512", it.header("Content-Range"))
                        assertArrayEquals(encrypted, it.body!!.bytes())
                    }
                }
            } finally { proxy.close(); network.close() }
        }
    }

    @Test fun documentMimeCompatibilityDoesNotAcceptOtherDocuments() {
        MockWebServer().use { server ->
            val types = listOf("application/vnd.apple.keynote", "text/vnd.qt.linguist", "text/vnd.trolltech.linguist")
            types.forEach { type -> server.enqueue(MockResponse().setHeader("Content-Type", type)
                .setBody("private document content")) }
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                val browser = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
                types.forEach {
                    browser.newCall(Request.Builder()
                        .url(proxy.url(server.url("/document").toString(), "https://page.example/watch"))
                        .build()).execute().use {
                        assertEquals(502, it.code)
                        assertFalse(it.body!!.string().contains("private document"))
                    }
                }
            } finally { proxy.close(); network.close() }
        }
    }

    @Test fun nativeHlsKeepsProfileContextForManifestKeySegmentAndRedirect() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:5,\nsegment.ts\n")
                .setHeader("Content-Type", "application/vnd.apple.mpegurl"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.ts")
                .setHeader("Set-Cookie", "session=renewed; Path=/"))
            server.enqueue(MockResponse().setResponseCode(206).setBody("video bytes")
                .setHeader("Content-Range", "bytes 0-10/100").setHeader("Accept-Ranges", "bytes"))
            val cookie = AtomicReference("session=initial")
            val ua = "Configured Android UA"
            val network = MediaRequestClient(ua, { cookie.get() }, { _, value -> cookie.set(value.substringBefore(';')) },
                MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                val browser = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
                val manifest = browser.newCall(Request.Builder()
                    .url(proxy.url(server.url("/master.m3u8").toString(), "https://page.example/watch"))
                    .header("User-Agent", "System media UA").build()).execute().use { it.body!!.string() }
                assertTrue(manifest.contains("URI=\"http://127.0.0.1:"))
                val segment = manifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
                browser.newCall(Request.Builder().url(segment).header("User-Agent", "System media UA")
                    .header("Range", "bytes=0-10").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("bytes 0-10/100", it.header("Content-Range"))
                    assertEquals("bytes", it.header("Accept-Ranges"))
                    assertEquals("video bytes", it.body!!.string())
                }
                repeat(3) { index ->
                    val request = server.takeRequest(1, TimeUnit.SECONDS) ?: error("Missing media request")
                    assertEquals(ua, request.getHeader("User-Agent"))
                    assertEquals(if (index == 2) "session=renewed" else "session=initial", request.getHeader("Cookie"))
                    assertEquals("https://page.example/watch", request.getHeader("Referer"))
                    if (index > 0) assertEquals("bytes=0-10", request.getHeader("Range"))
                }
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun headMetadataKeepsLengthAndSendsNoBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Length", "321").setHeader("Accept-Ranges", "bytes"))
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(Request.Builder()
                    .url(proxy.url(server.url("/movie.mp4").toString(), "https://page.example/"))
                    .head().build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("321", it.header("Content-Length"))
                    assertEquals(0, it.body!!.bytes().size)
                }
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun rewrittenManifestRequestsAndReturnsAFullRepresentation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:5,\nsegment.ts\n")
                .setHeader("Content-Type", "application/vnd.apple.mpegurl"))
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(Request.Builder()
                    .url(proxy.url(server.url("/master.m3u8").toString(), "https://page.example/"))
                    .header("Range", "bytes=0-12").header("If-None-Match", "old-manifest").build()).execute().use {
                    assertEquals(200, it.code)
                    assertNull(it.header("Content-Range"))
                    assertTrue(it.body!!.string().contains("/media?url="))
                }
                val request = server.takeRequest(1, TimeUnit.SECONDS) ?: error("Missing manifest request")
                assertNull(request.getHeader("Range"))
                assertNull(request.getHeader("If-None-Match"))
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun extensionlessPartialManifestIsRetriedAsFullBeforeRewriting() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("#EXTM3U\n")
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setHeader("Content-Range", "bytes 0-7/99"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:5,\nsegment.ts\n")
                .setHeader("Content-Type", "application/vnd.apple.mpegurl"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:5,\nsegment.ts\n")
                .setHeader("Content-Type", "application/vnd.apple.mpegurl"))
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(Request.Builder()
                    .url(proxy.url(server.url("/signed-source").toString(), "https://page.example/"))
                    .header("Range", "bytes=0-7").build()).execute().use {
                    assertEquals(200, it.code)
                    assertNull(it.header("Content-Range"))
                    assertTrue(it.body!!.string().contains("segment.ts"))
                }
                assertEquals("bytes=0-7", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
                val fullRequest = server.takeRequest(1, TimeUnit.SECONDS) ?: error("Missing full manifest retry")
                assertNull(fullRequest.getHeader("Range"))
                assertEquals("Configured UA", fullRequest.getHeader("User-Agent"))
                assertEquals("https://page.example/", fullRequest.getHeader("Referer"))
                val typedRequest = server.takeRequest(1, TimeUnit.SECONDS) ?: error("Missing typed HLS request")
                assertNull(typedRequest.getHeader("Range"))
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun extensionlessTextPlaylistUsesFinalUrlForEveryReference() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/play") {
                    MockResponse().setResponseCode(302).setHeader("Location", "/hls/signed-source")
                } else MockResponse().setBody("\uFEFF#EXTM3U\r\n#EXT-X-MAP:URI=\"../init.mp4\"\r\n" +
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin?x=1&y=2\"\r\n#EXTINF:5,\r\npart.ts\r\n")
                    .setHeader("Content-Type", "text/plain; charset=utf-8").setHeader("ETag", "original")
            }
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(Request.Builder()
                    .url(proxy.url(server.url("/play").toString(), "https://page.example/watch")).build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.request.url.encodedPath.endsWith(".m3u8"))
                    assertEquals("application/vnd.apple.mpegurl", it.header("Content-Type"))
                    assertNull(it.header("ETag"))
                    val manifest = it.body!!.string()
                    listOf("/init.mp4", "/hls/key.bin?x=1&y=2", "/hls/part.ts").forEach { path ->
                        assertTrue(manifest.contains(proxy.url(server.url(path).toString(), "https://page.example/watch")))
                    }
                }
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun genericPartialPlaylistIsRetriedWithoutRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("#EXTM3U\n")
                .setHeader("Content-Type", "application/octet-stream").setHeader("Content-Range", "bytes 0-7/40"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:5,\npart.ts\n")
                .setHeader("Content-Type", "application/octet-stream"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:5,\npart.ts\n")
                .setHeader("Content-Type", "application/octet-stream"))
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(Request.Builder()
                    .url(proxy.url(server.url("/play").toString(), "https://page.example/watch"))
                    .header("Range", "bytes=0-7").build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.body!!.string().contains(proxy.baseUrl))
                }
                assertEquals("bytes=0-7", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
                assertNull(server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
                assertNull(server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun detectedHlsRedirectGivesAndroidAParserHintWithoutCaching() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:5,\npart.ts\n").setHeader("Content-Type", "text/plain"))
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient.Builder().followRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(proxy.url(server.url("/signed").toString(), "https://page.example/"))
                        .build()).execute().use {
                        assertEquals(302, it.code)
                        assertEquals("no-store", it.header("Cache-Control"))
                        val location = requireNotNull(it.header("Location"))
                        assertEquals("/${proxy.token}/media.m3u8", java.net.URI(location).path)
                        assertTrue(location.contains("?url="))
                    }
                assertEquals(1, server.requestCount)
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun genericBinaryRangeRemainsByteForByteUnchanged() {
        MockWebServer().use { server ->
            val bytes = "#EXTM3UX".toByteArray() + ByteArray(256) { it.toByte() }
            server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(bytes))
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes 0-${bytes.size - 1}/1000"))
            val network = MediaRequestClient("Configured UA", addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(Request.Builder()
                    .url(proxy.url(server.url("/segment").toString(), "https://page.example/watch"))
                    .header("Range", "bytes=0-${bytes.size - 1}").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("bytes 0-${bytes.size - 1}/1000", it.header("Content-Range"))
                    assertArrayEquals(bytes, it.body!!.bytes())
                }
                assertEquals(1, server.requestCount)
            } finally { network.close(); proxy.close() }
        }
    }

    @Test fun crlfPlaylistCannotExceedInputByteLimit() {
        val source = Buffer().writeUtf8("#EXTM3U\r\n" + ("#" + "a".repeat(62) + "\r\n").repeat(2 * 1024 * 1024 / 65 + 1))
        var released = false
        val stream = HlsPlaylistStream(source, { it }, { released = true })
        assertThrows(IOException::class.java) { stream.use { it.readBytes() } }
        assertTrue(released)
    }

    @Test fun exactByteLimitWithoutFinalNewlineIsAccepted() {
        val header = "#EXTM3U\n"
        val last = "#" + "a".repeat(2 * 1024 * 1024 - header.length - 1)
        val source = Buffer().writeUtf8(header + last)
        var released = false
        val stream = HlsPlaylistStream(source, { it }, { released = true })
        assertEquals(header + last + "\n", stream.use { it.readBytes().toString(Charsets.UTF_8) })
        assertTrue(released)
    }

    @Test fun redirectCookiesBelongToEachNetworkHop() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            val finalUrl = second.url("/final").newBuilder().host("127.0.0.1").build()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", finalUrl)
                .setHeader("Set-Cookie", "first=renewed; Path=/"))
            second.enqueue(MockResponse().setBody("video").setHeader("Set-Cookie", "second=renewed; Path=/"))
            val saved = mutableListOf<Pair<String, String>>()
            val network = MediaRequestClient("Configured UA", {
                if (java.net.URI(it).host == "localhost") "first=initial" else "second=initial"
            }, { url, cookie -> saved += java.net.URI(url).host to cookie }, MediaAddressPolicy(allowPrivateForTests = true))
            try {
                network.execute(first.url("/start").newBuilder().host("localhost").build().toString(), "GET", emptyMap()).use {
                    assertEquals("video", it.body!!.string())
                }
                assertEquals("first=initial", first.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Cookie"))
                val target = second.takeRequest(1, TimeUnit.SECONDS) ?: error("Missing redirect target")
                assertEquals("second=initial", target.getHeader("Cookie"))
                assertEquals("Configured UA", target.getHeader("User-Agent"))
                assertEquals(listOf("localhost", "127.0.0.1"), saved.map { it.first })
            } finally { network.close() }
        } }
    }
}

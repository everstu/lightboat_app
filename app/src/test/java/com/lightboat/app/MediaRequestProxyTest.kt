package com.lightboat.app

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaRequestProxyTest {
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
            val network = MediaRequestClient(ua, { cookie.get() }, { _, value -> cookie.set(value.substringBefore(';')) })
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
            val network = MediaRequestClient("Configured UA")
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
            val network = MediaRequestClient("Configured UA")
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
            val network = MediaRequestClient("Configured UA")
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
            } finally { network.close(); proxy.close() }
        }
    }
}

package com.lightboat.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaRequestProxyTest {
    private val ua = "Configured iPhone UA/1.0"

    @Test fun nativePlayerHeadersCannotOverrideProfileAcrossPlaylistSegmentAndRedirect() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:5,\nsegment.ts\n")
                .setHeader("Content-Type", "application/vnd.apple.mpegurl"))
            upstream.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.ts"))
            upstream.enqueue(MockResponse().setResponseCode(206).setBody("video bytes")
                .setHeader("Content-Range", "bytes 0-10/100"))
            val network = ProfileRequestClient(ua, cookies = { "session=one" })
            val proxy = MediaRequestProxy(network)
            proxy.start()
            try {
                val browser = OkHttpClient()
                val manifest = browser.newCall(Request.Builder().url(proxy.url(upstream.url("/list.m3u8").toString(), "https://page.example/watch"))
                    .header("User-Agent", "System Android UA").build()).execute().use { it.body!!.string() }
                val segment = manifest.lineSequence().first { it.startsWith("http://127.0.0.1") }
                browser.newCall(Request.Builder().url(segment).header("User-Agent", "System Android UA")
                    .header("Range", "bytes=0-10").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("bytes 0-10/100", it.header("Content-Range"))
                    assertEquals("video bytes", it.body!!.string())
                }
                repeat(3) { index ->
                    val request = upstream.takeRequest()
                    assertEquals(ua, request.getHeader("User-Agent"))
                    assertEquals("session=one", request.getHeader("Cookie"))
                    assertEquals("https://page.example/watch", request.getHeader("Referer"))
                    if (index > 0) assertEquals("bytes=0-10", request.getHeader("Range"))
                }
                assertTrue(manifest.contains("URI=\"http://127.0.0.1:"))
            } finally { proxy.close(); network.close() }
        }
    }

    @Test fun everyRequestHopOverridesCaseInsensitiveUaAndWrongTokenCannotReachNetwork() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody("ok"))
            val network = ProfileRequestClient(ua)
            network.execute(upstream.url("/").toString(), "GET", mapOf("user-agent" to "Script Android UA")).use {
                assertEquals("ok", it.body!!.string())
            }
            assertEquals(ua, upstream.takeRequest().getHeader("User-Agent"))
            val proxy = MediaRequestProxy(network)
            proxy.start()
            try {
                val url = proxy.url(upstream.url("/").toString(), "https://page.example/").replace(proxy.token, "wrong-token")
                OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { assertEquals(403, it.code) }
                assertEquals(1, upstream.requestCount)
            } finally { proxy.close(); network.close() }
        }
    }

    @Test fun nestedPlaylistsMapAndKeyUrisUseFinalRedirectedBase() {
        val network = ProfileRequestClient(ua)
        val proxy = MediaRequestProxy(network)
        proxy.start()
        try {
            val text = proxy.rewritePlaylist("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n../sub/list.m3u8?a=1\n#EXT-X-KEY:METHOD=NONE\n", "https://cdn.example/a/master.m3u8", "https://page.example/")
            assertTrue(text.contains("https%3A%2F%2Fcdn.example%2Fa%2Finit.mp4"))
            assertTrue(text.contains("https%3A%2F%2Fcdn.example%2Fsub%2Flist.m3u8%3Fa%3D1"))
            assertTrue(text.contains("#EXT-X-KEY:METHOD=NONE"))
        } finally { proxy.close(); network.close() }
    }

    @Test fun upstreamLoopbackTargetsAreRejectedEvenWithAValidSessionToken() {
        val network = ProfileRequestClient(ua)
        val proxy = MediaRequestProxy(network)
        proxy.start()
        try {
            OkHttpClient().newCall(Request.Builder().url(proxy.url("http://127.0.0.2:1234/private", "https://page.example/"))
                .build()).execute().use { assertEquals(400, it.code) }
        } finally { proxy.close(); network.close() }
    }
}

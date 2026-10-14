package com.lightboat.app

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                val browser = OkHttpClient()
                val manifest = browser.newCall(Request.Builder().url(proxy.url(upstream.url("/list.m3u8").toString(), "https://page.example/watch"))
                    .header("User-Agent", "System Android UA").build()).execute().use { it.body!!.string() }
                val segment = manifest.lineSequence().first { it.startsWith("http://127.0.0.1") }
                browser.newCall(Request.Builder().url(segment).header("User-Agent", "System Android UA")
                    .header("Range", "bytes=0-10").header("If-None-Match", "old-response").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("bytes 0-10/100", it.header("Content-Range"))
                    assertEquals("no-store", it.header("Cache-Control"))
                    assertEquals("video bytes", it.body!!.string())
                }
                repeat(3) { index ->
                    val request = upstream.takeRequest()
                    assertEquals(ua, request.getHeader("User-Agent"))
                    assertEquals("session=one", request.getHeader("Cookie"))
                    assertEquals("https://page.example/watch", request.getHeader("Referer"))
                    if (index > 0) {
                        assertEquals("bytes=0-10", request.getHeader("Range"))
                        assertNull(request.getHeader("If-None-Match"))
                    }
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

    @Test fun mediaProxyPreservesAuthorizationValidatorsAndSegmentCachePolicy() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody("video bytes")
                .setHeader("Content-Type", "video/mp4")
                .setHeader("ETag", "segment-v1")
                .setHeader("Cache-Control", "private, max-age=60"))
            val network = ProfileRequestClient(ua)
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                OkHttpClient().newCall(Request.Builder()
                    .url(proxy.url(upstream.url("/video.mp4").toString(), "https://page.example/watch"))
                    .header("Authorization", "Bearer media-token")
                    .header("If-None-Match", "segment-v1")
                    .build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("segment-v1", it.header("ETag"))
                    assertEquals("private, max-age=60", it.header("Cache-Control"))
                    assertEquals("video bytes", it.body!!.string())
                }
                upstream.takeRequest().also {
                    assertEquals("Bearer media-token", it.getHeader("Authorization"))
                    assertEquals("segment-v1", it.getHeader("If-None-Match"))
                }
            } finally { proxy.close(); network.close() }
        }
    }

    @Test fun playlistRequestIsNotStarvedBySixOpenMediaConnections() {
        MockWebServer().use { upstream ->
            val holdsSeen = CountDownLatch(6)
            upstream.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path?.startsWith("/hold") == true) {
                        holdsSeen.countDown()
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    } else {
                        MockResponse().setBody("#EXTM3U\n#EXTINF:5,\nsegment.ts\n")
                            .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    }
            }
            val upstreamDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            val network = ProfileRequestClient(ua, OkHttpClient.Builder().dispatcher(upstreamDispatcher).build())
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            val browserDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            val browser = OkHttpClient.Builder().dispatcher(browserDispatcher)
                .callTimeout(2, TimeUnit.SECONDS).build()
            val heldCalls = mutableListOf<Call>()
            try {
                repeat(6) { index ->
                    browser.newCall(Request.Builder()
                        .url(proxy.url(upstream.url("/hold-$index").toString(), "https://page.example/watch"))
                        .build()).also { call ->
                        heldCalls += call
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, error: IOException) = Unit
                            override fun onResponse(call: Call, response: Response) { response.close() }
                        })
                    }
                }
                assertTrue("six media connections did not reach upstream", holdsSeen.await(2, TimeUnit.SECONDS))
                browser.newCall(Request.Builder()
                    .url(proxy.url(upstream.url("/next.m3u8").toString(), "https://page.example/watch"))
                    .build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.body!!.string().startsWith("#EXTM3U"))
                }
            } finally {
                heldCalls.forEach { it.cancel() }
                proxy.close()
                network.close()
            }
        }
    }
}

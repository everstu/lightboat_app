package com.lightboat.app

import android.os.SystemClock
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MediaMimePlaybackTest {
    @Test fun encryptedHlsActuallyPlaysWithQtSegmentsAndKeynoteKeyMime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val segment = instrumentation.context.assets.open("media-compatibility/clip.ts").use { it.readBytes() }
        val segmentType = AtomicReference("text/vnd.qt.linguist; charset=utf-8")
        val keys = AtomicInteger()
        val segments = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val (mime, bytes) = when (session.uri) {
                    "/master.m3u8" -> "application/octet-stream" to (
                        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x00000000000000000000000000000000\n" +
                        "#EXTINF:4.0,\nclip.ts\n#EXT-X-ENDLIST\n").toByteArray()
                    "/key" -> {
                        keys.incrementAndGet()
                        "application/vnd.apple.keynote" to ByteArray(16) { it.toByte() }
                    }
                    "/clip.ts" -> { segments.incrementAndGet(); segmentType.get() to segment }
                    else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
                }
                val ranged = session.headers["range"] != null
                val start = session.headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if (start !in bytes.indices) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "range")
                return newFixedLengthResponse(if (ranged) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                    mime, ByteArrayInputStream(bytes, start, bytes.size - start), (bytes.size - start).toLong()).apply {
                    if (ranged) addHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                    addHeader("Cache-Control", "no-store")
                }
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.listeningPort}"
        val network = MediaRequestClient("Media MIME playback test", addresses = MediaAddressPolicy(allowPrivateForTests = true))
        val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
        val configFile = File(context.filesDir, "config.json")
        val configBackup = configFile.takeIf { it.exists() }?.readBytes()
        var webView: WebView? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY)
            proxy.start()
            val activityScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activityScenario
            activityScenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    activity.setContentView(this)
                }
            }
            fun javascript(source: String): String? {
                val done = CountDownLatch(1)
                var result: String? = null
                activityScenario.onActivity { webView!!.evaluateJavascript(source) { result = it; done.countDown() } }
                assertTrue("JavaScript callback timed out", done.await(5, TimeUnit.SECONDS))
                return result
            }
            for (mime in listOf("text/vnd.qt.linguist; charset=utf-8", "text/vnd.trolltech.linguist; charset=utf-8")) {
                segmentType.set(mime)
                val keyCount = keys.get()
                val segmentCount = segments.get()
                val source = JSONObject.quote(proxy.url("$base/master.m3u8", "$base/watch"))
                val caseId = JSONObject.quote(mime)
                activityScenario.onActivity {
                    webView!!.loadDataWithBaseURL("$base/watch", """
                        <!doctype html><html><body><video id="player" muted autoplay playsinline></video>
                        <script>window.playbackCase=$caseId;const player=document.getElementById('player');player.src=$source;
                        player.play().catch(e=>window.playFailure=String(e));</script></body></html>
                    """.trimIndent(), "text/html", "UTF-8", null)
                }
                val playing = "window.playbackCase === $caseId && document.getElementById('player')?.currentTime > 1.0"
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (SystemClock.elapsedRealtime() < deadline && javascript(playing) != "true") {
                    SystemClock.sleep(100)
                }
                assertEquals("HLS did not play for $mime: " + javascript("window.playFailure || document.getElementById('player')?.error?.code || ''"), "true", javascript(playing))
                assertTrue("Player did not request its key", keys.get() > keyCount)
                assertTrue("Player did not request its encrypted segment", segments.get() > segmentCount)
            }
        } finally {
            scenario?.onActivity { webView?.apply { stopLoading(); destroy() } }
            scenario?.close()
            proxy.close(); network.close(); server.stop()
            configBackup?.let { configFile.writeBytes(it) } ?: configFile.delete()
        }
    }
}

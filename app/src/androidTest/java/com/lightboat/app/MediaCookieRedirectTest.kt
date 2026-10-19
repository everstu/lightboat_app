package com.lightboat.app

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaCookieRedirectTest {
    @Test fun redirectImmediatelyUsesCookiesWrittenByTheRealWebViewCookieManager() {
        val manager = CookieManager.getInstance()
        val cookieName = "media_audit_" + UUID.randomUUID().toString().replace("-", "")
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = if (session.uri == "/start")
                newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
                    addHeader("Location", "/final")
                    addHeader("Set-Cookie", "$cookieName=renewed; Path=/; HttpOnly")
                }
            else {
                val valid = session.headers["cookie"].orEmpty().contains("$cookieName=renewed")
                newFixedLengthResponse(if (valid) Response.Status.OK else Response.Status.UNAUTHORIZED,
                    "text/plain", if (valid) "media bytes" else "missing cookie")
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.listeningPort}"
        val network = MediaRequestClient("Media cookie audit", manager::getCookie, manager::setCookie)
        try {
            repeat(10) {
                manager.setCookie(url, "$cookieName=initial; Path=/; HttpOnly")
                network.execute("$url/start", "GET", emptyMap()).use { response ->
                    assertEquals("Redirect used stale WebView cookie", 200, response.code)
                    assertEquals("media bytes", response.body!!.string())
                }
            }
        } finally {
            network.close()
            server.stop()
            manager.setCookie(url, "$cookieName=; Max-Age=0; Path=/")
        }
    }
}

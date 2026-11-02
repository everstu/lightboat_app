package com.lightboat.app

import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Exercises the real Activity, document-start injection and Chromium HTTP callbacks. */
@RunWith(AndroidJUnit4::class)
class VisibleNavigationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private var originalConfig: ByteArray? = null
    private var originalCategory: String? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private val targetRequested = CountDownLatch(1)
    private val releasePage = CountDownLatch(1)
    @Volatile private var verifiedCookieReceived = false
    private val readyUserAgents = CopyOnWriteArrayList<String>()
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = when (session.uri) {
            "/user.js" -> newFixedLengthResponse(Response.Status.OK, "text/javascript", """
                // ==UserScript==
                // @name Visible navigation regression
                // @match *://*/*
                // @run-at document-start
                // @grant none
                // ==/UserScript==
                window.__scriptBeforePage = true;
            """.trimIndent())
            "/slow" -> {
                targetRequested.countDown()
                releasePage.await(15, TimeUnit.SECONDS)
                html(Response.Status.OK, "<h1>Loaded</h1>")
            }
            "/challenge" -> {
                targetRequested.countDown()
                html(Response.Status.FORBIDDEN, """
                    <h1 id="challenge">Verify to continue</h1>
                    <button id="verify" onclick="document.cookie='lightboat_test_verified=yes; Path=/'; location.href='/ready'">Continue</button>
                    <script>window.scriptWasReady = window.__scriptBeforePage === true;</script>
                """.trimIndent())
            }
            "/ready" -> {
                verifiedCookieReceived = session.headers["cookie"].orEmpty().contains("lightboat_test_verified=yes")
                readyUserAgents += session.headers["user-agent"].orEmpty()
                html(Response.Status.OK, "<h1 id='ready'>Ready</h1>")
            }
            else -> html(Response.Status.NOT_FOUND, "<h1>Not found</h1>")
        }

        private fun html(status: Response.Status, body: String): Response = newFixedLengthResponse(
            status, "text/html; charset=utf-8", "<!doctype html><html><body>$body</body></html>"
        ).apply { addHeader("Cache-Control", "no-store") }
    }
    private val baseUrl get() = "http://127.0.0.1:${server.listeningPort}"

    @Before fun setUp() {
        originalConfig = configFile.takeIf { it.exists() }?.readBytes()
        originalCategory = context.getSharedPreferences("profile_ui", 0).getString("categoryId", null)
        server.start()
    }

    @After fun tearDown() {
        releasePage.countDown()
        scenario?.close()
        server.stop()
        originalConfig?.let { configFile.writeBytes(it) } ?: configFile.delete()
        context.getSharedPreferences("profile_ui", 0).edit().putString("categoryId", originalCategory).commit()
    }

    @Test fun browserIsInteractiveBeforeTheInitialResponseCompletes() {
        launch("/slow")
        assertTrue("Target URL was not requested after script preparation", targetRequested.await(10, TimeUnit.SECONDS))
        scenario!!.onActivity { activity ->
            assertEquals("The loading overlay still hides the browser", View.GONE, activity.findViewById<View>(R.id.statusPanel).visibility)
            assertTrue(browser(activity)?.isShown == true)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.menuLayer).visibility)
        }
        releasePage.countDown()
    }

    @Test fun http403KeepsTheChallengeScriptAndVerificationNavigationAlive() {
        launch("/challenge")
        waitUntil("HTTP 403 destroyed or obscured the verification page") { activity ->
            browser(activity)?.isShown == true && activity.findViewById<View>(R.id.statusPanel).visibility == View.GONE &&
                browser(activity)?.progress == 100
        }
        assertEquals("true", javascript("!!document.getElementById('challenge') && window.scriptWasReady === true"))
        assertEquals("true", javascript("document.getElementById('verify').click(); true"))
        waitUntil("Verification did not navigate to the destination") { activity ->
            browser(activity)?.url == "$baseUrl/ready" && browser(activity)?.progress == 100
        }
        assertEquals("true", javascript("!!document.getElementById('ready') && window.__scriptBeforePage === true"))
        assertTrue("Verification cookie was lost", verifiedCookieReceived)
    }

    @Test fun genuineConnectionFailureStillShowsTheAppErrorAndRetry() {
        val unavailableUrl = "$baseUrl/unreachable"
        server.stop()
        launchUrl(unavailableUrl, "")
        waitUntil("A network failure no longer shows a retryable error") { activity ->
            activity.findViewById<View>(R.id.statusPanel).visibility == View.VISIBLE &&
                activity.findViewById<View>(R.id.retryButton).visibility == View.VISIBLE && browser(activity) == null
        }
    }

    @Test fun platformProfilesWithoutScriptsKeepVerificationCookiesAndTheirWebView() {
        for (platform in BrowserPlatform.entries) {
            val userAgent = when (platform) {
                BrowserPlatform.APPLE -> MobileUserAgent.appleSafari()
                BrowserPlatform.ANDROID -> AppConfig.DEFAULT_USER_AGENT
                BrowserPlatform.WINDOWS -> MobileUserAgent.windowsChrome(AppConfig.DEFAULT_USER_AGENT)
            }
            launchUrl("$baseUrl/challenge", "", userAgent, platform)
            waitUntil("The verification page was not visible for $platform") { activity ->
                browser(activity)?.progress == 100 && browser(activity)?.isShown == true
            }
            var originalView: AppWebView? = null
            scenario!!.onActivity { originalView = browser(it) }
            javascript("document.getElementById('verify').click(); true")
            waitUntil("Verification did not finish for $platform") { activity ->
                browser(activity)?.url == "$baseUrl/ready" && browser(activity)?.progress == 100
            }
            for (step in 1..3) {
                javascript("location.href='/ready?step=$step'; true")
                waitUntil("Verification navigation $step failed for $platform") { activity ->
                    browser(activity)?.url == "$baseUrl/ready?step=$step" && browser(activity)?.progress == 100
                }
                assertTrue("Verification cookie was lost for $platform", verifiedCookieReceived)
                assertEquals(userAgent, readyUserAgents.last())
                assertEquals("true", javascript("document.cookie.includes('lightboat_test_verified=yes')"))
                scenario!!.onActivity { assertSame("App recreated the verified WebView", originalView, browser(it)) }
            }
            scenario!!.close(); scenario = null
        }
    }

    private fun launch(path: String) = launchUrl("$baseUrl$path", "$baseUrl/user.js")

    private fun launchUrl(url: String, script: String, userAgent: String = AppConfig.DEFAULT_USER_AGENT,
        platform: BrowserPlatform = BrowserPlatform.ANDROID) {
        val config = if (script.isBlank()) AppConfig.EMPTY else AppConfig.EMPTY.upsertScript(SharedScript("visible", "Visible script", script))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config.upsert(
            Profile("navigation-test", "Navigation test", url, config.scriptLibrary.map { ProfileScript(it.id) }, userAgent, platform)))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun browser(activity: MainActivity): AppWebView? {
        val container = activity.findViewById<FrameLayout>(R.id.webContainer)
        return (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<AppWebView>().firstOrNull()
    }

    private fun waitUntil(message: String, condition: (MainActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun javascript(script: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        scenario!!.onActivity { activity ->
            requireNotNull(browser(activity)).evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue("JavaScript did not complete", done.await(3, TimeUnit.SECONDS))
        return result
    }
}

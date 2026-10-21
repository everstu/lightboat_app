package com.lightboat.app

import android.app.Dialog
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.userscript.ScriptValueStore
import fi.iki.elonen.NanoHTTPD
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real-Activity regression for the redirect-loop cache recovery entry.
 *
 * A cacheable 301 loop is served from the local HTTP server, so Chromium keeps
 * the redirect in its application-wide HTTP cache. After the failure page has
 * destroyed the failed WebView, the recovery button must still work; clicking
 * it clears that cache once and reopens the same profile.
 */
@RunWith(AndroidJUnit4::class)
class WebViewCacheRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private val runId = UUID.randomUUID().toString().take(8)
    private val profileId = "cache-recovery-$runId"
    private val scriptId = "cache-recovery-script-$runId"
    private val gmIdentity get() = "$scriptId:lightboat-test:Cache recovery GM probe"
    private val cachedImagePath get() = "/cached-$runId.png"
    private val framePagePath get() = "/frame-page-$runId"
    private val frameLoopAPath get() = "/frame-loop-a-$runId"
    private val frameLoopBPath get() = "/frame-loop-b-$runId"

    private var originalConfig: ByteArray? = null
    private var originalRecoveryMarker: ByteArray? = null
    private var originalInvalidConfigs: Set<String> = emptySet()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val temporaryWebViews = CopyOnWriteArrayList<WebView>()
    private val aRequests = CopyOnWriteArrayList<String>()
    private val bRequests = CopyOnWriteArrayList<String>()
    private val setupCookies = CopyOnWriteArrayList<String>()

    @Volatile private var loopRedirects = true
    @Volatile private var scriptStatus = 200
    @Volatile private var slowScript: CountDownLatch? = null
    private var tlsServer: MockWebServer? = null

    private val serverA = object : FixtureServer("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            aRequests += session.uri
            if (session.uri == "/setup") setupCookies += session.headers["cookie"].orEmpty()
            return when (session.uri) {
                "/setup" -> page(Response.Status.OK, SETUP_PAGE, cache = false)
                "/cleanup" -> page(Response.Status.OK,
                    "<!doctype html><html><body>cleanup</body></html>", cache = false)
                "/loop" -> if (loopRedirects) redirectLoop() else page(Response.Status.OK, SUCCESS_PAGE, cache = false)
                framePagePath -> page(Response.Status.OK, """
                    <!doctype html><html><body><h1 id="outer">Outer page</h1>
                    <iframe src="$frameLoopAPath"></iframe></body></html>
                """.trimIndent(), cache = false)
                frameLoopAPath -> redirectLoop(frameLoopBPath)
                frameLoopBPath -> redirectLoop(frameLoopAPath)
                "/probe.user.js" -> {
                    val pending = slowScript
                    pending?.await(20, TimeUnit.SECONDS)
                    if (scriptStatus != 200) {
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "unavailable")
                    } else newFixedLengthResponse(Response.Status.OK, "text/javascript", PROBE_SCRIPT)
                        .apply { addHeader("Cache-Control", "no-store") }
                }
                else -> page(Response.Status.NOT_FOUND, "<h1>Not found</h1>", cache = false)
            }
        }

        private fun redirectLoop(path: String = "/loop"): Response = newFixedLengthResponse(
            Response.Status.REDIRECT, "text/html; charset=utf-8", ""
        ).apply {
            addHeader("Location", path)
            // Chromium stores 301/308 in the application HTTP cache; the long
            // freshness lifetime makes the stale redirect survive a normal reopen.
            addHeader("Cache-Control", "public, max-age=3600")
        }
    }

    private val serverB = object : FixtureServer("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            bRequests += session.uri
            return when {
                session.uri == "/warm" -> page(Response.Status.OK, """
                    <!doctype html><html><body><h1 id="warm">Second origin</h1>
                    <img src="$cachedImagePath" width="8" height="8"></body></html>
                """.trimIndent(), cache = false)
                session.uri == cachedImagePath -> bytes(
                    Response.Status.OK, "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
                ).apply { addHeader("Cache-Control", "public, max-age=3600") }
                else -> page(Response.Status.NOT_FOUND, "<h1>Not found</h1>", cache = false)
            }
        }
    }

    private val baseA get() = "http://127.0.0.1:${serverA.listeningPort}"
    private val baseB get() = "http://127.0.0.1:${serverB.listeningPort}"

    @Before fun setUp() {
        originalConfig = configFile.takeIf(File::exists)?.readBytes()
        originalRecoveryMarker = File(context.filesDir, "config.recovery-required")
            .takeIf(File::exists)?.readBytes()
        originalInvalidConfigs = context.filesDir.listFiles().orEmpty()
            .filter { it.name.startsWith("config.invalid") }.map { it.name }.toSet()
        serverA.start()
        serverB.start()
        ScriptValueStore(context, gmIdentity).update("probe", JSONObject().put("type", "json").put("value", "gm-kept"))
    }

    @After fun tearDown() {
        clearFixtureStorage()
        scenario?.close()
        scenario = null
        instrumentation.runOnMainSync { temporaryWebViews.forEach { runCatching { it.destroy() } }; temporaryWebViews.clear() }
        runCatching { tlsServer?.shutdown() }
        tlsServer = null
        serverA.stop()
        serverB.stop()
        originalConfig?.let { configFile.writeBytes(it) } ?: runCatching { configFile.delete() }
        val marker = File(context.filesDir, "config.recovery-required")
        originalRecoveryMarker?.let { marker.writeBytes(it) } ?: runCatching { marker.delete() }
        context.filesDir.listFiles().orEmpty()
            .filter { it.name.startsWith("config.invalid") && it.name !in originalInvalidConfigs }
            .forEach { runCatching { it.delete() } }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(gmIdentity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        File(context.filesDir, "userscript-values/$hash.json").delete()
        File(context.filesDir, "userscript-values/$hash.json.bak").delete()
    }

    @Test fun cachedRedirectLoopRecoversAfterFailureWebViewDestructionAndKeepsUserData() {
        saveConfig("$baseA/setup", withScript = true)
        launch()
        waitFor("The cacheable 301 loop did not produce the redirect error page") { redirectLoopPage(it) }

        // Positive control for the application-wide cache fixture: the second
        // origin's cacheable image must come from the HTTP cache, not the server.
        warmSecondOriginCache()

        // With the server healthy, a plain retry still hits the cached 301.
        loopRedirects = false
        val loopRequestsBeforeRetry = requestsFor(serverA, "/loop")
        click(R.id.retryButton)
        waitFor("A plain retry unexpectedly bypassed the cached redirect") { redirectLoopPage(it) }
        assertEquals("The redirect loop was not served from the HTTP cache",
            loopRequestsBeforeRetry, requestsFor(serverA, "/loop"))

        // The recovery entry survives the failed WebView teardown and clears
        // the application-wide resource cache before reopening the profile.
        click(R.id.clearWebCacheRetryButton)
        waitForJs("Cache recovery did not reach the healthy page", "window.probeReady===true")
        assertTrue("The healthy page was not requested from the server after clearing the cache",
            requestsFor(serverA, "/loop") > loopRequestsBeforeRetry)
        assertEquals("true", javascript("window.probe.cookie===true"))
        assertEquals("\"kept\"", javascript("window.probe.local"))
        assertEquals("\"kept\"", javascript("window.probe.idb"))
        // The userscript's document-start GM handshake can complete just after
        // the page's own inline script, so observe it independently.
        waitForJs("The GM value was lost across cache recovery", "window.gmProbe==='gm-kept'")
        assertTrue("The recovered page did not send the preserved login cookie",
            setupCookies.size >= 2 && setupCookies.last().contains("lb_session=kept"))
        scenario!!.onActivity { activity ->
            val live = MainActivity::class.java.getDeclaredField("config")
                .apply { isAccessible = true }.get(activity) as AppConfig
            assertEquals(profileId, live.lastProfileId)
        }
        assertEquals("The failed profile was removed from the saved configuration",
            profileId, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load().lastProfileId)

        verifySecondOriginCacheWasCleared()
    }

    @Test fun aServerThatKeepsRedirectingDoesNotAutoRetryAfterCacheRecovery() {
        saveConfig("$baseA/loop", withScript = false)
        launch()
        waitFor("The persistent redirect loop did not produce the error page") { redirectLoopPage(it) }
        val loopsBefore = requestsFor(serverA, "/loop")
        val clearsBefore = cacheClearRequests()
        click(R.id.clearWebCacheRetryButton)
        waitFor("Cache recovery did not return to the error page while the server still looped") {
            redirectLoopPage(it)
        }
        assertEquals("One click must request exactly one cache clear", 1, cacheClearRequests() - clearsBefore)
        assertTrue("The cleared reopen did not reach the redirecting server",
            requestsFor(serverA, "/loop") > loopsBefore)
        val loopsAfter = requestsFor(serverA, "/loop")
        SystemClock.sleep(3_000)
        assertEquals("The app retried the redirect without another user action",
            loopsAfter, requestsFor(serverA, "/loop"))
        assertEquals("The app cleared the cache without another user action",
            1, cacheClearRequests() - clearsBefore)
        scenario!!.onActivity { activity ->
            assertEquals("The recovery entry was not offered after the second failure",
                View.VISIBLE, activity.findViewById<View>(R.id.clearWebCacheRetryButton).visibility)
            assertTrue(activity.findViewById<View>(R.id.clearWebCacheRetryButton).isEnabled)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.retryButton).visibility)
        }
    }

    @Test fun connectionCertificateSubframeScriptAndConfigFailuresDoNotOfferCacheRecovery() {
        saveConfig("http://127.0.0.1:1/unreachable", withScript = false)
        launch()
        waitFor("A connection failure no longer offers the ordinary error page") { errorPage(it) }
        scenario!!.onActivity { assertFalse(recoveryVisible(it)) }
        scenario!!.close(); scenario = null

        val tls = startUntrustedTlsServer()
        saveConfig(tls.url("/page").toString(), withScript = false)
        launch()
        waitFor("A certificate error no longer offers the ordinary error page") { errorPage(it) &&
            (it.findViewById<TextView>(R.id.statusDetail).text.contains("证书")) }
        scenario!!.onActivity { assertFalse(recoveryVisible(it)) }
        scenario!!.close(); scenario = null

        saveConfig("$baseA$framePagePath", withScript = false)
        launch()
        waitFor("The outer page did not load") { activity ->
            activity.findViewById<View>(R.id.statusPanel).visibility == View.GONE &&
                browser(activity)?.progress == 100
        }
        waitUntil("The iframe never entered its redirect loop; requests=$aRequests") {
            requestsFor(serverA, frameLoopAPath) + requestsFor(serverA, frameLoopBPath) >= 2
        }
        waitUntil("The iframe redirect loop was not reported to WebViewClient") {
            DebugLog.readAfter(null).lines.any { line ->
                line.contains("code=-9") &&
                    (line.contains(frameLoopAPath) || line.contains(frameLoopBPath))
            }
        }
        SystemClock.sleep(1_000)
        scenario!!.onActivity { activity ->
            assertEquals("A subresource redirect loop showed the main-frame error page",
                View.GONE, activity.findViewById<View>(R.id.statusPanel).visibility)
            assertFalse(recoveryVisible(activity))
            assertTrue(activity.findViewById<FrameLayout>(R.id.webContainer).childCount > 0)
        }
        scenario!!.close(); scenario = null

        scriptStatus = 500
        saveConfig("$baseA/setup", withScript = true)
        launch()
        waitForScriptFailure()
        scenario!!.onActivity { activity ->
            assertEquals(context.getString(R.string.script_error_title),
                activity.findViewById<TextView>(R.id.statusTitle).text.toString())
            assertFalse(recoveryVisible(activity))
        }
        clickDialogButton(R.id.cancelScriptVisit)
        scenario!!.close(); scenario = null

        configFile.writeText("{damaged config")
        launch()
        waitFor("A damaged configuration did not show the recovery page") { activity ->
            activity.findViewById<TextView>(R.id.statusTitle).text.toString() ==
                context.getString(R.string.config_recovery_title)
        }
        scenario!!.onActivity { assertFalse(recoveryVisible(it)) }
    }

    @Test fun switchingProfilesWhileRecoveryPreparesDoesNotClearTheNewProfilesCache() {
        val profileA = Profile("$profileId-a", "Loop with slow script", "$baseA/loop",
            listOf(ProfileScript(scriptId)), AppConfig.DEFAULT_USER_AGENT, BrowserPlatform.ANDROID)
        val profileB = Profile("$profileId-b", "Second profile", "$baseB/warm?run=$runId&profile=b",
            emptyList(), AppConfig.DEFAULT_USER_AGENT, BrowserPlatform.ANDROID)
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(
            AppConfig.EMPTY.upsertScript(SharedScript(scriptId, "GM probe", "$baseA/probe.user.js"))
                .upsert(profileA).upsert(profileB))
        launch()
        waitFor("The redirect loop profile did not fail") { redirectLoopPage(it) }
        warmSecondOriginCache()

        val clearsBefore = cacheClearRequests()
        val scriptRequestsBefore = requestsFor(serverA, "/probe.user.js")
        val pending = CountDownLatch(1)
        slowScript = pending
        click(R.id.clearWebCacheRetryButton)
        waitUntil("Recovery did not start re-preparing the script") {
            requestsFor(serverA, "/probe.user.js") > scriptRequestsBefore
        }
        openProfileDirectly(profileB)
        waitFor("The switched profile did not open") { activity ->
            activity.findViewById<View>(R.id.statusPanel).visibility == View.GONE &&
                browser(activity)?.progress == 100
        }
        pending.countDown()
        SystemClock.sleep(2_000)
        assertEquals("A stale recovery cleared the newly selected profile's cache",
            clearsBefore, cacheClearRequests())

        val before = cachedImageRequests()
        assertTrue("The second origin did not load for the verification", loadTemporary("$baseB/warm?run=$runId&verify=b"))
        SystemClock.sleep(500)
        assertEquals("The switched profile lost its cached resource", before, cachedImageRequests())
        assertEquals(profileB.id, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load().lastProfileId)
    }

    @Test fun retryingAfterAScriptFailureKeepsThePendingCacheClearForExactlyOneRun() {
        saveConfig("$baseA/setup", withScript = true)
        launch()
        waitFor("The redirect loop profile did not fail") { redirectLoopPage(it) }
        val clearsBefore = cacheClearRequests()
        val recoveryRequestsBefore = cacheRecoveryRequests()
        scriptStatus = 500
        click(R.id.clearWebCacheRetryButton)
        waitForScriptFailure()
        scenario!!.onActivity { assertFalse(recoveryVisible(it)) }
        assertEquals("The cache was cleared before the script retry was accepted",
            0, cacheClearRequests() - clearsBefore)

        loopRedirects = false
        scriptStatus = 200
        clickDialogButton(R.id.retryFailedScripts)
        waitForJs("The pending cache clear was lost across the script retry", "window.probeReady===true")
        assertEquals("The retry after a script failure did not clear exactly once",
            1, cacheClearRequests() - clearsBefore)
        assertTrue("The reopened page did not reach the healthy server",
            requestsFor(serverA, "/loop") >= 1)
        assertEquals("The script retry started a second recovery operation",
            1, cacheRecoveryRequests() - recoveryRequestsBefore)
    }

    @Test fun aFailedCacheClearRetriesTheClearInsteadOfAPlainReload() {
        saveConfig("$baseA/setup", withScript = true)
        launch()
        waitFor("The redirect loop profile did not fail") { redirectLoopPage(it) }
        val clearsBefore = cacheClearRequests()
        scenario!!.onActivity { activity ->
            activity.cacheClearAction = { throw IllegalStateException("injected clear failure") }
        }
        click(R.id.clearWebCacheRetryButton)
        waitFor("A failed cache clear did not show a retryable error page") { activity ->
            activity.findViewById<View>(R.id.statusPanel).visibility == View.VISIBLE &&
                activity.findViewById<View>(R.id.statusSpinner).visibility == View.GONE &&
                activity.findViewById<TextView>(R.id.statusDetail).text.toString() ==
                    context.getString(R.string.cache_clear_failed) &&
                activity.findViewById<View>(R.id.retryButton).visibility == View.VISIBLE &&
                !recoveryVisible(activity)
        }
        assertTrue("The clear failure was not logged",
            DebugLog.readAfter(null).lines.any { it.contains("[cache-recovery] clear failed") })
        assertEquals("A failed clear must not count as a completed clear",
            0, cacheClearRequests() - clearsBefore)

        // Restore the real clear and make the server healthy: a plain reopen
        // would still hit the cached 301, so success proves retry cleared again.
        scenario!!.onActivity { activity -> activity.cacheClearAction = { it.clearCache(true) } }
        loopRedirects = false
        click(R.id.retryButton)
        waitForJs("Retry after a failed clear did not clear the cache", "window.probeReady===true")
        assertEquals("Retry after a failed clear did not perform exactly one clear",
            1, cacheClearRequests() - clearsBefore)
        assertTrue("Retry after a failed clear did not reach the healthy server",
            requestsFor(serverA, "/loop") >= 1)
    }

    @Test fun continuingWithoutAFailedScriptAlsoKeepsThePendingCacheClear() {
        saveConfig("$baseA/setup", withScript = true)
        launch()
        waitFor("The redirect loop profile did not fail") { redirectLoopPage(it) }
        val clearsBefore = cacheClearRequests()
        scriptStatus = 500
        click(R.id.clearWebCacheRetryButton)
        waitForScriptFailure()
        assertEquals("The cache was cleared before the failed script was skipped",
            0, cacheClearRequests() - clearsBefore)

        loopRedirects = false
        clickDialogButton(R.id.continueWithoutScripts)
        waitForJs("Skipping the failed script lost the pending cache clear", "window.probeReady===true")
        assertEquals("Skipping the failed script did not clear exactly once",
            1, cacheClearRequests() - clearsBefore)
        assertTrue("The skipped-script visit did not reach the healthy server",
            requestsFor(serverA, "/loop") >= 1)
    }

    private fun saveConfig(url: String, withScript: Boolean) {
        var config = AppConfig.EMPTY
        if (withScript) config = config.upsertScript(SharedScript(scriptId, "GM probe", "$baseA/probe.user.js"))
        config = config.upsert(Profile(profileId, "Cache recovery", url,
            if (withScript) listOf(ProfileScript(scriptId)) else emptyList(),
            AppConfig.DEFAULT_USER_AGENT, BrowserPlatform.ANDROID))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun redirectLoopPage(activity: MainActivity): Boolean {
        val detail = activity.findViewById<TextView>(R.id.statusDetail).text.toString()
        val recovery = activity.findViewById<View>(R.id.clearWebCacheRetryButton)
        return activity.findViewById<View>(R.id.statusPanel).visibility == View.VISIBLE &&
            recovery.visibility == View.VISIBLE && recovery.isEnabled &&
            activity.findViewById<View>(R.id.retryButton).visibility == View.VISIBLE &&
            detail.contains("ERR_TOO_MANY_REDIRECTS") &&
            detail.contains(context.getString(R.string.redirect_loop_recovery_hint)) &&
            detail.contains(context.getString(R.string.redirect_loop_recovery_scope)) &&
            browser(activity) == null
    }

    private fun browser(activity: MainActivity): AppWebView? {
        val container = activity.findViewById<FrameLayout>(R.id.webContainer)
        return (0 until container.childCount).map { container.getChildAt(it) }
            .filterIsInstance<AppWebView>().firstOrNull()
    }

    private fun click(id: Int) = scenario!!.onActivity { it.findViewById<View>(id)!!.performClick() }

    private fun javascript(source: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        scenario!!.onActivity { activity ->
            val view = browser(activity)
            if (view == null) done.countDown() else view.evaluateJavascript(source) { result = it; done.countDown() }
        }
        if (!done.await(5, TimeUnit.SECONDS)) return null
        return result
    }

    private fun waitFor(message: String, timeoutMs: Long = 20_000, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("$message\n${DebugLog.readAfter(null).text.takeLast(6000)}")
    }

    /**
     * Polls page JavaScript from the test thread. It must not be called from
     * inside [waitFor]'s activity callback: [javascript] enters the main thread
     * again and its evaluation callback can never run there.
     */
    private fun waitForJs(message: String, expression: String, timeoutMs: Long = 20_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (javascript(expression) == "true") return
            SystemClock.sleep(100)
        }
        fail("$message\n${DebugLog.readAfter(null).text.takeLast(6000)}")
    }

    private fun waitUntil(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun errorPage(activity: MainActivity) =
        activity.findViewById<View>(R.id.statusPanel).visibility == View.VISIBLE &&
            activity.findViewById<View>(R.id.statusSpinner).visibility == View.GONE &&
            activity.findViewById<View>(R.id.retryButton).visibility == View.VISIBLE

    private fun recoveryVisible(activity: MainActivity) =
        activity.findViewById<View>(R.id.clearWebCacheRetryButton).visibility == View.VISIBLE

    private fun cacheClearRequests() =
        DebugLog.readAfter(null).lines.count { it.contains("[cache-recovery] clear requested") }

    private fun cacheRecoveryRequests() =
        DebugLog.readAfter(null).lines.count { it.contains("[cache-recovery] requested") }

    private fun nativeDialog(activity: MainActivity): Dialog? =
        MainActivity::class.java.getDeclaredField("nativeDialog")
            .apply { isAccessible = true }.get(activity) as Dialog?

    private fun waitForScriptFailure() = waitFor("The script failure dialog did not appear") { activity ->
        (nativeDialog(activity) as? ScriptFailureDialog)?.isShowing == true
    }

    private fun clickDialogButton(id: Int) = scenario!!.onActivity { activity ->
        (nativeDialog(activity) as ScriptFailureDialog).findViewById<View>(id)!!.performClick()
    }

    private fun openProfileDirectly(profile: Profile) = scenario!!.onActivity { activity ->
        MainActivity::class.java.getDeclaredMethod("openProfile", Profile::class.java)
            .apply { isAccessible = true }.invoke(activity, profile)
    }

    private fun startUntrustedTlsServer(): MockWebServer {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        return MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("<h1>untrusted</h1>"))
            start()
            tlsServer = this
        }
    }

    private fun requestsFor(server: NanoHTTPD, path: String): Int =
        (if (server === serverA) aRequests else bRequests).count { it == path }

    private fun cachedImageRequests() = bRequests.count { it == cachedImagePath }

    private fun loadTemporary(url: String, script: String? = null): Boolean {
        val done = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            val view = WebView(activity)
            temporaryWebViews += view
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (script == null) {
                        done.countDown()
                    } else view.evaluateJavascript(script) { done.countDown() }
                }
            }
            view.loadUrl(url)
        }
        return done.await(15, TimeUnit.SECONDS)
    }

    private fun warmSecondOriginCache() {
        assertTrue("The second origin did not load", loadTemporary("$baseB/warm?run=$runId&step=1"))
        waitUntil("The second origin's image was never requested") { cachedImageRequests() >= 1 }
        assertEquals("The fixture requested a cacheable image more than once", 1, cachedImageRequests())
        assertTrue("The second origin did not load again", loadTemporary("$baseB/warm?run=$runId&step=2"))
        SystemClock.sleep(500)
        assertEquals("The local server fixture did not warm the HTTP cache",
            1, cachedImageRequests())
    }

    private fun verifySecondOriginCacheWasCleared() {
        val before = cachedImageRequests()
        assertTrue("The second origin did not load after recovery", loadTemporary("$baseB/warm?run=$runId&after=1"))
        waitUntil("Clearing did not evict another origin's cached resource") {
            cachedImageRequests() > before
        }
    }

    private fun clearFixtureStorage() {
        if (scenario == null) return
        // Clean the fixture origin's page storage and cookie while the local
        // server is still running; the recovery cache itself is app-scoped and
        // is either cleared or left to the next test's unique port.
        runCatching {
            loadTemporary("$baseA/cleanup",
                "localStorage.clear();sessionStorage.clear();indexedDB.deleteDatabase('lb-db');" +
                    "document.cookie='lb_session=; Path=/; Max-Age=0';true")
        }
        CookieManager.getInstance().setCookie(baseA, "lb_session=; Path=/; Max-Age=0")
        CookieManager.getInstance().flush()
    }

    private abstract class FixtureServer(host: String, port: Int) : NanoHTTPD(host, port) {
        protected fun page(status: Response.Status, body: String, cache: Boolean): Response =
            newFixedLengthResponse(status, "text/html; charset=utf-8", body).apply {
                addHeader("Cache-Control", if (cache) "public, max-age=3600" else "no-store")
            }

        protected fun bytes(status: Response.Status, mime: String, data: ByteArray): Response =
            newFixedLengthResponse(status, mime, java.io.ByteArrayInputStream(data), data.size.toLong())
    }

    private companion object {
        private val SETUP_PAGE = """
            <!doctype html><html><body>
            <h1 id="setup">Setup</h1>
            <script>
            document.cookie = 'lb_session=kept; Path=/; Max-Age=3600';
            localStorage.setItem('lb_local', 'kept');
            const open = indexedDB.open('lb-db', 1);
            open.onupgradeneeded = () => open.result.createObjectStore('kv');
            open.onsuccess = () => {
              const db = open.result;
              const tx = db.transaction('kv', 'readwrite');
              tx.objectStore('kv').put('kept', 'probe');
              tx.oncomplete = () => { db.close(); location.replace('/loop'); };
            };
            </script></body></html>
        """.trimIndent()

        private val SUCCESS_PAGE = """
            <!doctype html><html><body>
            <h1 id="ok">Recovered</h1>
            <script>
            window.probeReady = false;
            const open = indexedDB.open('lb-db', 1);
            open.onsuccess = () => {
              const db = open.result;
              const get = db.transaction('kv').objectStore('kv').get('probe');
              get.onsuccess = () => {
                window.probe = {
                  cookie: document.cookie.includes('lb_session=kept'),
                  local: localStorage.getItem('lb_local'),
                  idb: get.result
                };
                db.close();
                window.probeReady = true;
              };
            };
            </script></body></html>
        """.trimIndent()

        private val PROBE_SCRIPT = """
            // ==UserScript==
            // @name Cache recovery GM probe
            // @namespace lightboat-test
            // @match *://*/*
            // @run-at document-start
            // @grant GM_getValue
            // ==/UserScript==
            window.gmProbe = GM_getValue('probe', 'missing');
        """.trimIndent()
    }
}

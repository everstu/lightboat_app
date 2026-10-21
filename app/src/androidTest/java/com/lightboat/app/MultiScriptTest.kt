package com.lightboat.app

import android.os.SystemClock
import android.widget.FrameLayout
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.userscript.ScriptValueStore
import com.lightboat.app.userscript.UserScriptGroup
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class MultiScriptTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private val id = "multi-" + UUID.randomUUID()
    private var backup: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var failDownload = true
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests += session.uri
            if (session.uri.endsWith("failed.user.js") && failDownload) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "unavailable")
            if (session.uri == "/bad.js") return newFixedLengthResponse(Response.Status.OK, "text/javascript", "const = ;")
            if (session.uri == "/delayed.user.js" || session.uri == "/bad.user.js" || session.uri == "/unmatched.user.js") {
                val match = if (session.uri == "/unmatched.user.js") "https://unmatched.example/*" else "*://*/*"
                val body = if (session.uri == "/delayed.user.js") "window.idleReady=true;" else "const = ;"
                return newFixedLengthResponse(Response.Status.OK, "text/javascript", """
                    // ==UserScript==
                    // @name Delayed startup
                    // @match $match
                    // @grant none
                    // ==/UserScript==
                    $body
                """.trimIndent())
            }
            if (session.uri == "/throw.user.js") return newFixedLengthResponse(Response.Status.OK, "text/javascript", """
                // ==UserScript==
                // @name Failed startup
                // @match *://*/*
                // @run-at document-start
                // @grant none
                // ==/UserScript==
                throw new Error('startup regression');
            """.trimIndent())
            val script = session.uri.substringAfterLast('/').substringBefore('.')
            if (session.uri.endsWith(".user.js")) return newFixedLengthResponse(Response.Status.OK, "text/javascript", """
                // ==UserScript==
                // @name Same name
                // @namespace multi-test
                // @match *://*/*
                // @run-at document-start
                // @grant GM_getValue
                // @grant GM.setValue
                // @grant GM_addStyle
                // @grant GM_registerMenuCommand
                // ==/UserScript==
                window.order=window.order||[];window.order.push('$script');
                window['initial_$script']=GM_getValue('owner','empty');
                GM_addStyle('.ad-$script{display:none!important}');
                GM_registerMenuCommand('Same command',()=>{window['clicked_$script']=true});
                GM.setValue('owner','$script').then(()=>{window['ready_$script']=true});
            """.trimIndent())
            if (session.uri.endsWith(".js")) return newFixedLengthResponse(Response.Status.OK, "text/javascript",
                "window.rawOrder=window.rawOrder||[];window.rawOrder.push('$script');")
            return newFixedLengthResponse(Response.Status.OK, "text/html", """
                <!doctype html><html><body><aside class="ad-a">a</aside><aside class="ad-b">b</aside>
                <a id="next" href="/next">Next</a></body></html>
            """.trimIndent())
        }
    }
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private fun fixture() = AppConfig(id, listOf(Profile(id, "Multi", "$base/page", listOf(
        ProfileScript("a"), ProfileScript("off", false), ProfileScript("b")))))
        .copy(scriptLibrary = listOf(SharedScript("a", "First", "$base/a.user.js"),
            SharedScript("b", "Second", "$base/b.user.js"), SharedScript("off", "Disabled", "$base/off.user.js")))

    @Before fun setUp() { backup = configFile.takeIf(File::exists)?.readBytes(); server.start() }
    @After fun tearDown() {
        scenario?.close(); server.stop()
        backup?.let(configFile::writeBytes) ?: configFile.delete()
        for (scriptId in listOf("a", "b")) {
            for (identity in listOf("$id:$scriptId:multi-test:Same name", "$scriptId:multi-test:Same name")) {
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                File(context.filesDir, "userscript-values/$hash.json").delete()
                File(context.filesDir, "userscript-values/$hash.json.bak").delete()
            }
        }
    }

    @Test fun theSameSharedScriptReadsItsGmValueAfterSwitchingProfiles() {
        val first = Profile("$id-a", "First profile", "$base/first-page", listOf(ProfileScript("a")))
        val second = Profile("$id-b", "Second profile", "$base/second-page", listOf(ProfileScript("a")))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig(first.id, listOf(first, second),
            scriptLibrary = listOf(SharedScript("a", "First", "$base/a.user.js"))))
        launch()
        waitFor { javascript("window.ready_a===true") == "true" }
        assertEquals("\"empty\"", javascript("window.initial_a"))
        scenario!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("openProfile", Profile::class.java)
                .apply { isAccessible = true }.invoke(activity, second)
        }
        waitFor { javascript("window.initial_a==='a' && window.ready_a===true") == "true" }
    }

    @Test fun sameNamedScriptsKeepIndependentStorageMenusAndStylesAcrossNavigationAndReload() {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(fixture())
        launch()
        waitReady()
        assertEquals("\"empty\"", javascript("window.initial_a"))
        assertEquals("\"empty\"", javascript("window.initial_b"))
        assertEquals("[\"a\",\"b\"]", javascript("JSON.stringify(window.order)").let { JSONObject("{\"v\":$it}").getString("v") })
        assertFalse(requests.contains("/off.user.js"))
        assertEquals("\"none\"", javascript("getComputedStyle(document.querySelector('.ad-a')).display"))
        assertEquals("\"none\"", javascript("getComputedStyle(document.querySelector('.ad-b')).display"))
        scenario!!.onActivity { activity ->
            val group = host(activity)
            assertEquals(2, group.menus.size)
            assertTrue(group.menus[0].title.startsWith("First"))
            group.invoke(group.menus[1])
        }
        assertEquals("true", javascript("window.clicked_b===true && !window.clicked_a"))
        javascript("document.querySelector('#next').click();true")
        waitReady()
        assertEquals("\"a\"", javascript("window.initial_a"))
        assertEquals("\"b\"", javascript("window.initial_b"))
        scenario!!.close(); scenario = null
        launch(); waitReady()
        assertEquals("\"a\"", javascript("window.initial_a"))
        assertEquals("\"b\"", javascript("window.initial_b"))
        assertEquals(2, requests.count { it == "/a.user.js" })
    }

    @Test fun ordinaryScriptsFollowTheSelectedOrderAndDisabledScriptsAreNotDownloaded() {
        val config = fixture().copy(scriptLibrary = fixture().scriptLibrary.map { it.copy(url = "$base/${it.id}.js") })
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        launch()
        waitFor { javascript("JSON.stringify(window.rawOrder)") == "\"[\\\"a\\\",\\\"b\\\"]\"" }
        assertFalse(requests.contains("/off.js"))
    }

    @Test fun aSyntaxErrorDoesNotPreventAnotherScriptOrTheInteractionGuard() {
        val config = fixture().copy(scriptLibrary = listOf(SharedScript("a", "Broken", "$base/bad.js"),
            SharedScript("b", "Good", "$base/good.js"), SharedScript("off", "Disabled", "$base/off.js")))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        launch()
        waitFor { javascript("Array.isArray(window.rawOrder) && window.rawOrder[0]==='good'") == "true" }
        waitFailure()
        clickFailure(R.id.continueWithoutScripts)
        assertEquals("true", javascript("!document.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true}))"))
    }

    @Test fun downloadFailureWaitsForConsentThenKeepsSuccessfulScriptsAndSavedSettings() {
        val config = fixture().upsertScript(SharedScript("b", "Failed script", "$base/failed.user.js"))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        launch()
        waitFailure()
        captureFailure()
        scenario!!.onActivity { activity -> assertTrue(failureDialog(activity).findViewById<TextView>(R.id.scriptFailureDetails)!!.text.contains("Failed script")) }
        assertFalse(requests.contains("/page"))
        assertFalse(requests.contains("/off.user.js"))
        clickFailure(R.id.continueWithoutScripts)
        waitFor { javascript("window.ready_a===true") == "true" }
        assertEquals("true", javascript("window.ready_b===undefined"))
        assertEquals(config, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
    }

    @Test fun failedDownloadsCanBeRetriedAndThePreviouslyFailedScriptRuns() {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(fixture().upsertScript(SharedScript("b", "Retry", "$base/failed.user.js")))
        launch(); waitFailure()
        failDownload = false
        clickFailure(R.id.retryFailedScripts)
        waitFor { javascript("window.ready_a===true && window.ready_failed===true") == "true" }
        assertEquals(2, requests.count { it == "/failed.user.js" })
    }

    @Test fun cancellingFailureDoesNotOpenTheWebsiteAndOldContinueButtonsCannotReopenIt() {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(fixture().upsertScript(SharedScript("b", "Failed", "$base/failed.user.js")))
        launch(); waitFailure()
        var oldContinue: View? = null
        scenario!!.onActivity { oldContinue = failureDialog(it).findViewById(R.id.continueWithoutScripts) }
        clickFailure(R.id.cancelScriptVisit)
        scenario!!.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.profilesPanel).isShown)
            oldContinue!!.performClick()
        }
        SystemClock.sleep(400)
        assertFalse(requests.contains("/page"))
    }

    @Test fun allFailedScriptsCanBeSkippedWithoutChangingTheProfile() {
        val config = fixture().upsertScript(SharedScript("a", "First failure", "$base/first-failed.user.js"))
            .upsertScript(SharedScript("b", "Second failure", "$base/second-failed.user.js"))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        launch(); waitFailure()
        scenario!!.onActivity { activity ->
            val details = failureDialog(activity).findViewById<TextView>(R.id.scriptFailureDetails)!!.text.toString()
            assertTrue(details.contains("First failure") && details.contains("Second failure"))
        }
        clickFailure(R.id.continueWithoutScripts)
        waitFor { javascript("document.querySelector('#next')!==null") == "true" }
        assertEquals(config, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
    }

    @Test fun userscriptStartupErrorsOfferContinueAndLeaveOtherScriptsRunning() {
        val config = fixture().upsertScript(SharedScript("a", "Startup failure", "$base/throw.user.js"))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        launch(); waitFailure()
        scenario!!.onActivity { activity -> assertTrue(failureDialog(activity).findViewById<TextView>(R.id.scriptFailureDetails)!!.text.contains("startup regression")) }
        clickFailure(R.id.continueWithoutScripts)
        waitFor { javascript("window.ready_b===true") == "true" }
        assertTrue(requests.contains("/page"))
    }

    @Test fun userscriptSyntaxErrorsAreReportedEvenWhenNoBridgeSessionWasCreated() {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(
            fixture().upsertScript(SharedScript("a", "Invalid userscript", "$base/bad.user.js")))
        launch(); waitFailure()
        clickFailure(R.id.continueWithoutScripts)
        waitFor { javascript("window.ready_b===true") == "true" }
    }

    @Test fun defaultIdleScriptsAndUnmatchedScriptsDoNotTriggerFalseFailures() {
        val config = fixture().upsertScript(SharedScript("a", "Idle script", "$base/delayed.user.js"))
            .upsertScript(SharedScript("off", "Unmatched script", "$base/unmatched.user.js"))
            .let { value -> value.upsert(value.profiles.single().copy(scripts = listOf(ProfileScript("a"), ProfileScript("off"), ProfileScript("b")))) }
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        launch()
        waitFor { javascript("window.idleReady===true && window.ready_b===true") == "true" }
        SystemClock.sleep(300)
        scenario!!.onActivity { activity ->
            val dialog = MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity)
            assertFalse(dialog is ScriptFailureDialog && dialog.isShowing)
        }
    }

    private fun failureDialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("nativeDialog")
        .apply { isAccessible = true }.get(activity) as ScriptFailureDialog
    private fun waitFailure() = waitFor {
        var showing = false
        scenario!!.onActivity { activity ->
            val dialog = MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity)
            showing = dialog is ScriptFailureDialog && dialog.isShowing
        }
        showing
    }
    private fun clickFailure(id: Int) = scenario!!.onActivity { failureDialog(it).findViewById<View>(id)!!.performClick() }

    private fun captureFailure() {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "script-failure.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    private fun waitReady() = waitFor { javascript("window.ready_a===true && window.ready_b===true") == "true" }
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) { if (check()) return; SystemClock.sleep(50) }
        fail("Multi-script state not ready; " + DebugLog.readAfter(null).text.takeLast(4000))
    }
    private fun host(activity: MainActivity) = MainActivity::class.java.getDeclaredField("userScriptHost")
        .apply { isAccessible = true }.get(activity) as UserScriptGroup
    private fun javascript(source: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        scenario!!.onActivity { activity ->
            val frame = activity.findViewById<FrameLayout>(R.id.webContainer)
            val web = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<AppWebView>().firstOrNull()
            if (web == null) done.countDown() else web.evaluateJavascript(source) { result = it; done.countDown() }
        }
        assertTrue(done.await(3, TimeUnit.SECONDS)); return result
    }
}

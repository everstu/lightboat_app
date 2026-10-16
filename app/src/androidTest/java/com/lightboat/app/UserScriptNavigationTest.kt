package com.lightboat.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.userscript.ScriptValueStore
import com.lightboat.app.userscript.UserScriptHost
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

@RunWith(AndroidJUnit4::class)
class UserScriptNavigationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val profileId = "script-navigation-" + UUID.randomUUID()
    private val configFile get() = File(context.filesDir, "config.json")
    private val storeIdentity get() = "$profileId:lightboat-test:Navigation GM regression"
    private var backup: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var delayedStart: (() -> Unit)? = null
    private val cacheSize = 2 * 1024 * 1024
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = when (session.uri) {
            "/script.user.js" -> newFixedLengthResponse(Response.Status.OK, "text/javascript", """
                // ==UserScript==
                // @name Navigation GM regression
                // @namespace lightboat-test
                // @match *://*/*
                // @run-at document-start
                // @grant GM_getValue
                // @grant GM.setValue
                // @grant GM_addStyle
                // @grant GM_registerMenuCommand
                // ==/UserScript==
                window.scriptRuns=(window.scriptRuns||0)+1;
                window.cacheLength=GM_getValue('cache','').length;
                GM_addStyle('.advertisement{display:none!important;}');
                GM_registerMenuCommand('Page '+location.pathname,()=>{
                    window.menuClicks=(window.menuClicks||0)+1;
                    GM.setValue('clicked',location.pathname).then(()=>window.menuSaved=true).catch(e=>window.gmError=e.message);
                });
                window.scriptReady=true;
            """.trimIndent())
            else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                "<!doctype html><html><body><aside class='advertisement'>Ad</aside><h1>${session.uri}</h1></body></html>")
        }.apply { addHeader("Cache-Control", "no-store") }
    }
    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    @Before fun setUp() {
        backup = configFile.takeIf(File::exists)?.readBytes()
        server.start()
        ScriptValueStore(context, storeIdentity).update("cache", JSONObject().put("type", "json").put("value", "x".repeat(cacheSize)))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY.upsert(
            Profile(profileId, "Navigation test", "$base/first", "$base/script.user.js")))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Initial userscript did not execute") { javascript("window.scriptReady===true") == "true" }
    }

    @After fun tearDown() {
        scenario?.close(); server.stop()
        backup?.let(configFile::writeBytes) ?: configFile.delete()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(storeIdentity.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        File(context.filesDir, "userscript-values/$hash.json").delete()
    }

    @Test fun latePageStartedDoesNotEraseNewDocumentCommandsOrGmSession() {
        scenario!!.onActivity { activity ->
            val view = browser(activity)
            val delegate = view.webViewClient
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url == "$base/next") delayedStart = { delegate.onPageStarted(view, url, favicon) }
                    else delegate.onPageStarted(view, url, favicon)
                }
                override fun onPageCommitVisible(view: WebView, url: String?) = delegate.onPageCommitVisible(view, url)
                override fun onPageFinished(view: WebView, url: String?) = delegate.onPageFinished(view, url)
            }
            view.loadUrl("$base/next")
        }
        waitFor("New document did not register its command before the delayed callback") {
            javascript("location.pathname==='/next' && window.scriptReady===true") == "true" && menuTitles() == listOf("Page /next")
        }
        assertEquals(cacheSize.toString(), javascript("window.cacheLength"))
        scenario!!.onActivity { requireNotNull(delayedStart).invoke() }
        assertEquals("A late onPageStarted erased the current document's command", listOf("Page /next"), menuTitles())
        invokeCommand()
        waitFor("Command could no longer write GM storage after onPageStarted") { javascript("window.menuSaved===true") == "true" }
        assertEquals("\"none\"", javascript("getComputedStyle(document.querySelector('.advertisement')).display"))
        assertEquals("1", javascript("window.scriptRuns"))
    }

    @Test fun anOpenBrowserMenuReflectsCommandsRegisteredOrRemovedLater() {
        lateinit var button: View
        scenario!!.onActivity { activity ->
            host(activity).resetDocuments()
            MainActivity::class.java.getDeclaredMethod("showBrowserMenu").apply { isAccessible = true }.invoke(activity)
            val dialog = MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }
                .get(activity) as android.app.Dialog
            button = dialog.findViewById(R.id.menuScriptCommands)
            assertFalse(button.isEnabled)
        }
        javascript("window.dispatchEvent(new PageTransitionEvent('pagehide',{persisted:true}));window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true}));")
        waitFor("Open menu did not enable its script command button after registration") {
            var enabled = false
            scenario!!.onActivity { enabled = button.isEnabled }
            enabled
        }
        scenario!!.onActivity { activity ->
            host(activity).resetDocuments()
            assertFalse(button.isEnabled)
        }
    }

    private fun browser(activity: MainActivity): AppWebView {
        val container = activity.findViewById<FrameLayout>(R.id.webContainer)
        return (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<AppWebView>().first()
    }
    private fun host(activity: MainActivity): UserScriptHost = MainActivity::class.java.getDeclaredField("userScriptHost")
        .apply { isAccessible = true }.get(activity) as UserScriptHost
    private fun menuTitles(): List<String> {
        var result = emptyList<String>()
        scenario!!.onActivity { result = host(it).menus.map(UserScriptHost.Menu::title) }
        return result
    }
    private fun invokeCommand() { scenario!!.onActivity { activity -> host(activity).let { it.invoke(it.menus.single()) } } }
    private fun waitFor(message: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return
            SystemClock.sleep(50)
        }
        fail(message + " logs=" + DebugLog.readAfter(null).text.takeLast(4000))
    }
    private fun javascript(script: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        scenario!!.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.webContainer)
            val view = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<AppWebView>().firstOrNull()
            if (view == null) { result = "null"; done.countDown() }
            else view.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        return result
    }
}

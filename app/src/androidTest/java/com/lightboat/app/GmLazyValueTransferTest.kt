package com.lightboat.app

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.userscript.ScriptValueStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GmLazyValueTransferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val identity = "lazy-large-" + UUID.randomUUID()
    private val scriptId = "lazy-large"
    private val namespace = "lightboat-test"
    private val name = "Lazy large GM read"
    private val canonical = "$scriptId:$namespace:$name"
    private val size = 5 * 1024 * 1024 + 37
    private val configFile get() = File(context.filesDir, "config.json")
    private var backup: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = when (session.uri) {
            "/lazy.user.js" -> newFixedLengthResponse(Response.Status.OK, "text/javascript", """
                // ==UserScript==
                // @name $name
                // @namespace $namespace
                // @match *://*/*
                // @run-at document-start
                // @grant GM.getValue
                // @grant GM_registerMenuCommand
                // ==/UserScript==
                window.lazyStarted = true;
                window.lazyMenu = GM_registerMenuCommand('Lazy menu', () => { window.menuClicked = true; });
                window.readLarge = async () => {
                  try {
                    const value = await GM.getValue('cache');
                    window.cachedLength = value.length;
                    window.cachedOK = value[0] === 'x' && value[value.length - 1] === 'x';
                  } catch (error) { window.gmError = error.message; }
                };
                window.readMissing = async () => { window.missing = await GM.getValue('missing', 'fallback'); };
                window.readLarge();
                window.readMissing();
            """.trimIndent())
            else -> newFixedLengthResponse(Response.Status.OK, "text/html",
                "<!doctype html><html><body><h1>Lazy GM</h1></body></html>")
        }.apply { addHeader("Cache-Control", "no-store") }
    }
    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    @Before fun setUp() {
        backup = configFile.takeIf(File::exists)?.readBytes()
        server.start()
        ScriptValueStore(context, canonical).update("cache", JSONObject().put("type", "json").put("value", "x".repeat(size)))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY
            .upsertScript(SharedScript(scriptId, "Lazy large", "$base/lazy.user.js"))
            .upsert(Profile(identity, "Lazy GM", "$base/page", listOf(ProfileScript(scriptId)))))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun tearDown() {
        scenario?.close(); server.stop()
        backup?.let(configFile::writeBytes) ?: configFile.delete()
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        File(context.filesDir, "userscript-values/$hash.json").delete()
        File(context.filesDir, "userscript-values/$hash.json.bak").delete()
    }

    @Test fun aMultiMegabyteGetValueResponseIsChunkedAndFullyRestored() {
        waitFor("Lazy script did not start", "window.lazyStarted===true")
        waitFor("Large lazy GM read failed: " + javascript("window.gmError"), "window.cachedLength===$size")
        assertEquals("true", javascript("window.cachedOK"))
        assertEquals("\"fallback\"", javascript("window.missing"))
    }

    @Test fun lazyScriptReconnectsItsMenuAfterBfcacheRestore() {
        waitFor("Lazy script did not start", "window.lazyStarted===true")
        waitFor("Lazy script menu was not registered", "window.lazyMenu!==undefined")
        assertEquals(1, hostMenus().size)
        javascript("window.dispatchEvent(new PageTransitionEvent('pagehide',{persisted:true}));true")
        waitFor("Lazy script menu was not disposed with its document") { hostMenus().isEmpty() }
        javascript("window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true}));true")
        waitFor("Lazy script menu was not restored after BFCache pageshow") { hostMenus().size == 1 }
        scenario!!.onActivity { activity ->
            val group = MainActivity::class.java.getDeclaredField("userScriptHost")
                .apply { isAccessible = true }.get(activity) as com.lightboat.app.userscript.UserScriptGroup
            group.invoke(group.menus.single())
        }
        waitFor("Restored lazy script menu callback was not connected", "window.menuClicked===true")
    }

    private fun hostMenus(): List<Any> {
        var menus: List<Any> = emptyList()
        scenario!!.onActivity { activity ->
            val group = MainActivity::class.java.getDeclaredField("userScriptHost")
                .apply { isAccessible = true }.get(activity) as com.lightboat.app.userscript.UserScriptGroup
            menus = group.menus
        }
        return menus
    }

    private fun waitFor(message: String, expression: String) {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            if (javascript(expression) == "true") return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun waitFor(message: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

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

package com.lightboat.app

import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import android.webkit.WebStorage
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.userscript.ScriptStorage
import com.lightboat.app.userscript.ScriptValueStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** Exercises the current external no-ad.js through the actual WebView and native GM bridge. */
@RunWith(AndroidJUnit4::class)
class NoAdDeviceAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scriptId = "no-ad-acceptance-${UUID.randomUUID()}"
    private val scriptInput = File(context.filesDir, "acceptance-no-ad.js")
    private val configFile get() = File(context.filesDir, "config.json")
    private var originalConfig: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var originalScript: String
    private lateinit var canonical: String
    private lateinit var first: Profile
    private lateinit var second: Profile
    private val ruleRequests = AtomicInteger()
    @Volatile private var rulesAvailable = true
    @Volatile private var ruleText = ""
    private val server: NanoHTTPD = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val response = when (session.uri) {
                "/no-ad.user.js" -> {
                    val replacement = "const SOURCES = [{url: '${base}/rules.txt'}];"
                    val source = Regex("const SOURCES = \\[.*?\\n    \\];", RegexOption.DOT_MATCHES_ALL)
                        .replaceFirst(originalScript, replacement)
                    check(source != originalScript) { "Could not replace the script's SOURCES fixture" }
                    newFixedLengthResponse(Response.Status.OK, "text/javascript; charset=utf-8", source)
                }
                "/rules.txt" -> {
                    ruleRequests.incrementAndGet()
                    if (rulesAvailable) newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", ruleText)
                    else newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "offline fixture")
                }
                else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", """
                    <!doctype html><html><head><script>
                    window.alert = text => { window.acceptanceStatus = String(text); };
                    </script></head><body><h1>Native no-ad acceptance</h1>
                    <div id="rule-target" class="fixture-block-a7c91">Fixture target</div>
                    <div id="control">Visible control</div></body></html>
                """.trimIndent())
            }
            response.addHeader("Cache-Control", "no-store")
            return response
        }
    }
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private val otherOrigin get() = "http://localhost:${server.listeningPort}"
    private val sourceKey get() = "no-ad:shared-rule-source:v1:$base/rules.txt"
    private val sourceUrls get() = JSONArray().put("$base/rules.txt")
    private val sourceFingerprint get() = sourceUrls.toString()
    private val storeFile: File get() {
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(context.filesDir, "userscript-values/$hash.json")
    }

    @Before fun setUp() {
        originalConfig = configFile.takeIf(File::exists)?.readBytes()
        assertTrue("Push the current no-ad.js to ${scriptInput.path} before running this test", scriptInput.isFile)
        originalScript = scriptInput.readText(Charsets.UTF_8)
        val name = Regex("(?m)^//\\s*@name\\s+(.+)$").find(originalScript)!!.groupValues[1].trim()
        val namespace = Regex("(?m)^//\\s*@namespace\\s+(.+)$").find(originalScript)!!.groupValues[1].trim()
        canonical = "$scriptId:$namespace:$name"
        server.start()
        first = Profile("$scriptId-first", "No-ad first", "$base/page", listOf(ProfileScript(scriptId)))
        second = Profile("$scriptId-second", "No-ad second", "$otherOrigin/page", listOf(ProfileScript(scriptId)))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig(first.id, listOf(first, second),
            scriptLibrary = listOf(SharedScript(scriptId, "Actual no-ad", "$base/no-ad.user.js"))))
    }

    @After fun tearDown() {
        scenario?.close()
        ScriptStorage.exclusive { }
        originalConfig?.let(configFile::writeBytes) ?: configFile.delete()
        if (::canonical.isInitialized) AtomicFile(storeFile).delete()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WebStorage.getInstance().deleteOrigin(base)
            WebStorage.getInstance().deleteOrigin(otherOrigin)
        }
        server.stop()
    }

    @Test fun fourMiBRulesAreReusedAcrossProfilesOriginsAndColdActivityStarts() {
        ruleText = rules(4 * 1024 * 1024 + 4096)
        assertTrue(ruleText.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024)
        launch()
        waitFor("The actual script did not persist its first rule download") { completeStore() != null }
        waitForHiddenTarget()
        assertEquals(1, ruleRequests.get())
        val initial = completeStore()!!
        assertEquals(1, initial.keys().asSequence().count { it.startsWith("no-ad:shared-rule-source:v1:") })
        val source = unpack(initial.getJSONObject(sourceKey).getJSONObject("value"))
        assertTrue(source.getString("text").toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024)
        assertFalse(initial.getJSONObject(META_KEY).getJSONObject("value").getJSONArray("sources")
            .getJSONObject(0).has("text"))
        assertTrue(storeFile.length() <= 10 * 1024 * 1024)

        javascript("localStorage.setItem('no-ad:v1:custom','##.first-origin-only');true")
        rulesAvailable = false
        switchTo(second)
        waitFor("The second origin was not opened") { javascript("location.hostname==='localhost'") == "true" }
        waitForHiddenTarget()
        assertEquals("null", javascript("localStorage.getItem('no-ad:v1:custom')"))
        assertEquals("null", javascript("localStorage.getItem('no-ad:v1:cache')"))
        assertEquals(1, ruleRequests.get())

        scenario!!.close(); scenario = null
        ScriptStorage.exclusive { }
        launch()
        waitForHiddenTarget()
        assertEquals("true", javascript("location.hostname==='localhost'"))
        assertEquals(1, ruleRequests.get())
        assertNotNull(completeStore())
    }

    @Test fun aLargeLegacyMirrorMigratesToOneSharedShardAndKeepsItsConfirmedBackup() {
        ruleText = rules(6 * 1024 * 1024 + 4096)
        val source = JSONObject().put("url", "$base/rules.txt").put("updatedAt", System.currentTimeMillis())
            .put("text", ruleText)
        val legacy = JSONObject().put("schema", 1).put("profile", "optimized-v1")
            .put("sourceKey", sourceFingerprint).put("attemptedAt", 0).put("sources", JSONArray().put(source))
        writeValue(META_KEY, legacy)
        rulesAvailable = false
        launch()
        waitFor("The actual script did not migrate the large legacy mirror", 180_000) { completeStore() != null }
        waitForHiddenTarget()
        waitFor("The migration recovery copy was not confirmed", 180_000) {
            showStatus().contains("迁移期间已保留本站恢复副本")
        }
        javascript("window.acceptanceIdbReady=false;(async()=>{const r=indexedDB.open('no-ad-rule-cache',1);r.onsuccess=()=>{const db=r.result;const q=db.transaction('rules').objectStore('rules').get('current');q.onsuccess=()=>{window.acceptanceIdbReady=!!q.result;db.close();};};})();true")
        waitFor("The migration's IndexedDB recovery copy was missing") { javascript("window.acceptanceIdbReady===true") == "true" }
        assertEquals(0, ruleRequests.get())
        assertTrue(storeFile.length() <= 10 * 1024 * 1024)
        scenario!!.close(); scenario = null
        ScriptStorage.exclusive { }
        launch()
        waitForHiddenTarget()
        assertEquals(0, ruleRequests.get())
    }

    @Test fun theGrantedModernDeleteApiReclaimsAnObsoleteSourceShard() {
        ruleText = rules(128 * 1024)
        val now = System.currentTimeMillis()
        val active = JSONObject().put("schema", 1).put("profile", "optimized-v1")
            .put("url", "$base/rules.txt").put("updatedAt", now).put("text", ruleText)
        val retiredUrl = "$base/removed-rules.txt"
        val retiredKey = "no-ad:shared-rule-source:v1:$retiredUrl"
        writeValue(sourceKey, active)
        writeValue(retiredKey, JSONObject().put("schema", 1).put("profile", "optimized-v1")
            .put("url", retiredUrl).put("updatedAt", now).put("text", "old-rules-" + "x".repeat(1024 * 1024)))
        writeValue(INDEX_KEY, JSONObject().put("schema", 1).put("profile", "optimized-v1")
            .put("urls", JSONArray().put("$base/rules.txt").put(retiredUrl)))
        writeValue(META_KEY, JSONObject().put("schema", 1).put("profile", "optimized-v1")
            .put("storage", "shards-v1").put("sourceKey", sourceFingerprint).put("attemptedAt", 0)
            .put("sources", JSONArray().put(JSONObject().put("url", "$base/rules.txt").put("updatedAt", now))))
        rulesAvailable = false
        launch()
        waitForHiddenTarget()
        waitFor("The real GM.deleteValue bridge did not reclaim the obsolete shard") {
            readStore()?.let { !it.has(retiredKey) && it.getJSONObject(INDEX_KEY).getJSONObject("value")
                .getJSONArray("urls").length() == 1 } == true
        }
        assertEquals(0, ruleRequests.get())
        assertEquals(1, completeStore()!!.keys().asSequence().count { it.startsWith("no-ad:shared-rule-source:v1:") })
    }

    private fun rules(minimumBytes: Int): String {
        val random = Random(8642091)
        val hex = "0123456789abcdef"
        return buildString {
            append("! Title: Native fixture\n! Version: 1\n")
            append("127.0.0.1##.fixture-block-a7c91\nlocalhost##.fixture-block-a7c91\n")
            var index = 0
            while (length < minimumBytes) {
                append("||ad-").append(index++).append(".fixture.test/")
                repeat(1024) { append(hex[random.nextInt(16)]) }
                append("\n")
            }
        }
    }

    private fun writeValue(key: String, value: JSONObject) = ScriptStorage.exclusive {
        ScriptValueStore(context, canonical).update(key, JSONObject().put("type", "json").put("value", value))
    }

    private fun readStore(): JSONObject? = ScriptStorage.exclusive {
        if (!storeFile.exists()) null else AtomicFile(storeFile).openRead().bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun completeStore(): JSONObject? {
        val root = readStore() ?: return null
        val meta = root.optJSONObject(META_KEY)?.optJSONObject("value") ?: return null
        if (meta.optString("storage") != "shards-v1" || !root.has(sourceKey)) return null
        return root
    }

    private fun unpack(value: JSONObject): JSONObject {
        if (value.optString("format") != "gzip-base64-v1") return value
        val bytes = Base64.decode(value.getString("data"), Base64.DEFAULT)
        return GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    private fun switchTo(profile: Profile) = scenario!!.onActivity { activity ->
        MainActivity::class.java.getDeclaredMethod("openProfile", Profile::class.java)
            .apply { isAccessible = true }.invoke(activity, profile)
    }

    private fun waitForHiddenTarget() = waitFor("Rules were not applied to the actual page") {
        javascript("!!document.getElementById('rule-target') && getComputedStyle(document.getElementById('rule-target')).display==='none' && getComputedStyle(document.getElementById('control')).display!=='none'") == "true"
    }

    private fun showStatus(): String {
        javascript("window.acceptanceStatus='';true")
        scenario!!.onActivity { activity ->
            val group = MainActivity::class.java.getDeclaredField("userScriptHost")
                .apply { isAccessible = true }.get(activity) as? com.lightboat.app.userscript.UserScriptGroup
            group?.menus?.firstOrNull { it.title.contains("查看状态") }?.let(group::invoke)
        }
        SystemClock.sleep(50)
        val raw = javascript("window.acceptanceStatus||''") ?: return ""
        return JSONObject("{\"value\":$raw}").optString("value")
    }

    private fun waitFor(message: String, timeout: Long = 120_000, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        var lastError: Exception? = null
        while (SystemClock.elapsedRealtime() < until) {
            try { if (check()) return } catch (error: Exception) { lastError = error }
            SystemClock.sleep(100)
        }
        fail("$message; last=${lastError?.message}; logs=${DebugLog.readAfter(null).text.takeLast(5000)}")
    }

    private fun javascript(source: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        scenario!!.onActivity { activity ->
            val frame = activity.findViewById<FrameLayout>(R.id.webContainer)
            val web = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<AppWebView>().firstOrNull()
            if (web == null) done.countDown() else web.evaluateJavascript(source) { result = it; done.countDown() }
        }
        assertTrue("WebView did not answer", done.await(5, TimeUnit.SECONDS))
        return result
    }

    companion object {
        private const val META_KEY = "no-ad:shared-rule-cache:v1"
        private const val INDEX_KEY = "no-ad:shared-rule-source-index:v1"
    }
}

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
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GmLargeTransferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val identity = "gm-large-" + UUID.randomUUID()
    private val configFile get() = File(context.filesDir, "config.json")
    private var backup: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private val size = 5 * 1024 * 1024 + 37
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = when (session.uri) {
            "/script.user.js" -> newFixedLengthResponse(Response.Status.OK, "text/javascript", """
                // ==UserScript==
                // @name Large GM regression
                // @namespace lightboat-test
                // @match *://*/*
                // @run-at document-start
                // @grant GM.xmlHttpRequest
                // @grant GM.setValue
                // @grant GM_getValue
                // @grant GM_addValueChangeListener
                // @grant GM_deleteValue
                // @connect 127.0.0.1
                // ==/UserScript==
                window.gmReady = true;
                window.cachedLength = (GM_getValue('cache', '') || '').length;
                window.fetchText = async () => {
                  try {
                    const r = await GM.xmlHttpRequest({url:location.origin+'/text',timeout:60000});
                    window.textLength=r.responseText.length;
                    window.textEnd=r.responseText.slice(-3);
                    await GM.setValue('cache',r.responseText);
                    window.saved=true;
                  } catch(e) { window.gmError=e.message; }
                };
                window.fetchBinary = async () => {
                  try {
                    const r=await GM.xmlHttpRequest({url:location.origin+'/binary',responseType:'arraybuffer',timeout:60000});
                    const b=new Uint8Array(r.response);
                    window.binaryOK=b.length===$size && b[0]===0 && b[255]===255 && b[b.length-1]===((b.length-1)%256);
                  } catch(e) { window.gmError=e.message; }
                };
                window.fetchLimit = async (path) => {
                  try { const r=await GM.xmlHttpRequest({url:location.origin+path,timeout:60000}); window.limitLength=r.responseText.length; }
                  catch(e) { window.limitError=e.message; }
                };
                window.storeLarge = async (value) => { try { await GM.setValue('cache',value); window.largeSaved=true; } catch(e) {window.storeError=e.message;} };
                GM_addValueChangeListener('shared', (key,oldValue,value,remote) => {if(remote) {window.remoteDeleted=value===undefined; window.remoteLength=value?.length;}});
                window.share = async () => {await GM.setValue('shared','z'.repeat(400000)); window.sharedSaved=true;};
                window.deleteShared = () => GM_deleteValue('shared');
                window.writeCounter = value => GM.setValue('counter', value);
                window.readCounter = () => GM_getValue('counter', 0);
            """.trimIndent())
            "/text" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", patternStream(size, false), size.toLong())
            "/small" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            "/binary" -> newFixedLengthResponse(Response.Status.OK, "application/octet-stream", patternStream(size, true), size.toLong())
            "/exact" -> newFixedLengthResponse(Response.Status.OK, "text/plain", patternStream(10 * 1024 * 1024, false), (10 * 1024 * 1024).toLong())
            "/over" -> newChunkedResponse(Response.Status.OK, "text/plain", patternStream(10 * 1024 * 1024 + 1, false))
            else -> newFixedLengthResponse(Response.Status.OK, "text/html", "<!doctype html><html><body><h1>GM</h1></body></html>")
        }.apply { addHeader("Cache-Control", "no-store") }
    }
    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    private fun patternStream(size: Int, binary: Boolean) = object : InputStream() {
        var position = 0
        override fun read(): Int = if (position >= size) -1 else if (binary) position++ % 256 else { position++; 'x'.code }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val count = minOf(length, size - position)
            for (i in 0 until count) buffer[offset + i] = if (binary) ((position + i) % 256).toByte() else 'x'.code.toByte()
            position += count
            return count
        }
    }

    @Before fun setUp() {
        backup = configFile.takeIf(File::exists)?.readBytes()
        server.start()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY
            .upsertScript(SharedScript("large", "Large script", "$base/script.user.js"))
            .upsert(Profile(identity, "GM test", "$base/page", listOf(ProfileScript("large", legacyStorage = true)))))
        launch()
    }

    @After fun tearDown() {
        scenario?.close(); server.stop()
        backup?.let(configFile::writeBytes) ?: configFile.delete()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$identity:large:lightboat-test:Large GM regression".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        File(context.filesDir, "userscript-values/$hash.json").delete()
        File(context.filesDir, "userscript-values/$hash.json.bak").delete()
    }

    @Test fun largeResponseStorageReloadBinaryAndRemoteFrameRemainCompatible() {
        javascript("window.fetchText(); true")
        waitFor("5 MiB text download/storage failed", "window.saved===true")
        assertEquals(size.toString(), javascript("window.textLength"))
        scenario!!.close(); launch()
        assertEquals(size.toString(), javascript("window.cachedLength"))
        javascript("window.fetchBinary(); true")
        waitFor("Binary response was corrupted", "window.binaryOK===true")
        javascript("const f=document.createElement('iframe');f.src='/frame';f.id='frame';document.body.appendChild(f);true")
        waitFor("Child script did not start", "document.getElementById('frame').contentWindow.gmReady===true")
        javascript("window.share();true")
        waitFor("Large remote value was not synchronized", "document.getElementById('frame').contentWindow.remoteLength===400000")
        assertEquals("true", javascript("window.sharedSaved===true"))
        javascript("window.deleteShared();true")
        waitFor("Remote deletion did not arrive", "document.getElementById('frame').contentWindow.remoteDeleted===true")
    }

    @Test fun exactDownloadLimitSucceedsAndUnknownLengthOverLimitFails() {
        javascript("window.fetchLimit('/exact');true")
        waitFor("Exactly 10 MiB should be accepted", "window.limitLength===10485760")
        javascript("window.limitLength=0;window.fetchLimit('/over');true")
        waitFor("Unknown-size response exceeded the 10 MiB limit", "typeof window.limitError==='string'")
        assertTrue(javascript("window.limitError")!!.contains("10 MiB"))
        assertEquals("0", javascript("window.limitLength"))
    }

    @Test fun storeLimitIsAtomicAndCanColdLoadMoreThanOneMiB() {
        val store = ScriptValueStore(context, "test-storage-$identity")
        try {
            store.update("kept", JSONObject().put("type", "json").put("value", "ok"))
            store.update("large", JSONObject().put("type", "json").put("value", "中🙂".repeat(400000)))
            val loaded = ScriptValueStore(context, "test-storage-$identity")
            assertEquals(400000 * 3, JSONObject(loaded.encoded("large")!!).getString("value").length)
            try {
                loaded.update("too-large", JSONObject().put("type", "json").put("value", "x".repeat(10 * 1024 * 1024)))
                fail("Oversize storage was accepted")
            } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("10 MiB")) }
            val after = ScriptValueStore(context, "test-storage-$identity")
            assertEquals("ok", JSONObject(after.encoded("kept")!!).getString("value"))
            assertNull(after.encoded("too-large"))
        } finally {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest("test-storage-$identity".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            File(context.filesDir, "userscript-values/$hash.json").delete()
        }
    }

    @Test fun simultaneousFrameWritesConvergeToThePersistedValue() {
        javascript("const frame=document.createElement('iframe');frame.src='/frame';frame.id='frame';document.body.appendChild(frame);true")
        waitFor("Child script did not start", "document.getElementById('frame').contentWindow.gmReady===true")
        javascript("""
            void (async()=>{
              try {
                const child=document.getElementById('frame').contentWindow;
                for(let i=0;i<20;i++) {
                  await Promise.all([window.writeCounter(i*2+1),child.writeCounter(i*2+2)]);
                  await new Promise(resolve=>setTimeout(resolve,50));
                  if(window.readCounter()!==child.readCounter()) throw new Error('Frame snapshots diverged');
                }
                window.countersSaved=true;
              } catch(error) { window.gmError=error.message; }
            })();true
        """.trimIndent())
        waitFor("Concurrent frame writes did not converge", "window.countersSaved===true")
        val persisted = JSONObject(ScriptValueStore(context,
            "$identity:large:lightboat-test:Large GM regression").encoded("counter")!!).getInt("value")
        assertEquals(persisted.toString(), javascript("window.readCounter()"))
        assertEquals(persisted.toString(), javascript("document.getElementById('frame').contentWindow.readCounter()"))
    }

    @Test fun pagesCannotEnumerateNativeGmBridgesButCapturedApisStillWorkInAllFrames() {
        assertEquals("false", javascript("Object.getOwnPropertyNames(window).some(k=>/^LightboatGM_[a-f0-9]+$/.test(k))"))
        javascript("window.fetchLimit('/small');true")
        waitFor("A captured bridge must still receive native replies", "window.limitLength===2")
        javascript("const frame=document.createElement('iframe');frame.src='/frame';frame.id='frame';document.body.appendChild(frame);true")
        waitFor("Child script did not start", "document.getElementById('frame').contentWindow.gmReady===true")
        assertEquals("false", javascript("Object.getOwnPropertyNames(document.getElementById('frame').contentWindow).some(k=>/^LightboatGM_[a-f0-9]+$/.test(k))"))
        javascript("window.writeCounter(123);true")
        waitFor("Private bridge no longer writes GM values", "document.getElementById('frame').contentWindow.readCounter()===123")
    }

    @Test fun aMissingChunkAcknowledgementDoesNotPermanentlyDisableTheDocumentTransport() {
        // Keep JS busy past the native ACK deadline. The bridge itself is private.
        scenario!!.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.webContainer)
            val view = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<AppWebView>().first()
            view.evaluateJavascript("window.fetchLimit('/small');const until=performance.now()+17000;while(performance.now()<until){};true", null)
        }
        SystemClock.sleep(18000)
        waitFor("The missing ACK did not time out", "typeof window.limitError==='string'")
        assertTrue(javascript("window.limitError")!!.contains("超时"))

        javascript("window.limitError=null;window.limitLength=0;window.fetchLimit('/small');true")
        waitFor("The next request did not finish", "window.limitLength===2 || typeof window.limitError==='string'")
        assertEquals("The document transport remained closed: ${javascript("window.limitError")}", "2", javascript("window.limitLength"))

        javascript("const frame=document.createElement('iframe');frame.src='/frame';frame.id='frame';document.body.appendChild(frame);true")
        waitFor("Child script did not start", "document.getElementById('frame').contentWindow.gmReady===true")
        javascript("document.getElementById('frame').contentWindow.share();true")
        waitFor("The child write did not complete", "document.getElementById('frame').contentWindow.sharedSaved===true")
        assertEquals("Value notifications did not recover", "400000", javascript("window.remoteLength"))
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); waitFor("Script did not start", "window.gmReady===true") }
    private fun waitFor(message: String, expression: String) {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            if (javascript(expression) == "true") return
            val error = javascript("window.gmError || null")
            if (error != "null") fail("$message: $error")
            SystemClock.sleep(100)
        }
        fail(message + ": " + DebugLog.readAfter(null).text.takeLast(4000))
    }
    private fun javascript(script: String): String? {
        var result: String? = null
        val done = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.webContainer)
            val view = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<AppWebView>().firstOrNull()
            if (view == null) { result = "null"; done.countDown() } else view.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        return result
    }
}

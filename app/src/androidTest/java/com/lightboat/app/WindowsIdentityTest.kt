package com.lightboat.app

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Check the browser's real UA-CH API, including requests made without any userscripts. */
@RunWith(AndroidJUnit4::class)
class WindowsIdentityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private var backup: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private val received = CountDownLatch(1)
    @Volatile private var identity: JSONObject? = null
    @Volatile private var requestHeaders = emptyMap<String, String>()
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/identity") {
                val body = mutableMapOf<String, String>()
                session.parseBody(body)
                requestHeaders = session.headers.toMap()
                identity = JSONObject(body["postData"].orEmpty())
                received.countDown()
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html", """
                <!doctype html><html><body><h1>Browser identity</h1><script>
                (async()=>{
                  const data=navigator.userAgentData;
                  const result={sameObject:data===navigator.userAgentData,
                    type:Object.prototype.toString.call(data), low:data.toJSON(),
                    empty:await data.getHighEntropyValues([]),
                    architecture:await data.getHighEntropyValues(['architecture']),
                    full:await data.getHighEntropyValues(['architecture','bitness','fullVersionList','platformVersion','uaFullVersion'])};
                  await fetch('/identity',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(result)});
                })();
                </script></body></html>
            """.trimIndent()).apply { addHeader("Cache-Control", "no-store") }
        }
    }
    private val url get() = "http://127.0.0.1:${server.listeningPort}/"
    private lateinit var userAgent: String

    @Before fun setUp() {
        backup = configFile.takeIf(File::exists)?.readBytes()
        instrumentation.runOnMainSync { userAgent = MobileUserAgent.windowsChrome(MobileUserAgent.default(context)) }
        server.start()
    }
    @After fun tearDown() {
        scenario?.close(); server.stop()
        backup?.let(configFile::writeBytes) ?: configFile.delete()
    }

    @Test fun windowsProfilePreservesTheNativeClientHintsObjectAndRequestedFields() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA))
        val profile = Profile("windows-identity", "Windows identity", url,
            userAgent = userAgent, platform = BrowserPlatform.WINDOWS)
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY.upsert(profile))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val result = result()
        assertTrue("UA-CH object changes on every navigator read", result.getBoolean("sameObject"))
        assertEquals("Native UA-CH was replaced with a plain JS object", "[object NavigatorUAData]", result.getString("type"))
        checkRequestedFields(result)
        assertEquals(userAgent, requestHeaders["user-agent"])
        assertEquals("\"Windows\"", requestHeaders["sec-ch-ua-platform"])
        assertEquals("?0", requestHeaders["sec-ch-ua-mobile"])
        assertEquals("Windows", result.getJSONObject("low").getString("platform"))
        val brands = result.getJSONObject("low").getJSONArray("brands")
        for (i in 0 until brands.length()) {
            val brand = brands.getJSONObject(i)
            assertTrue("Header and JS brands differ", requestHeaders["sec-ch-ua"].orEmpty()
                .contains("\"${brand.getString("brand")}\";v=\"${brand.getString("version")}\""))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    @Test fun windowsFallbackKeepsOneObjectAndDoesNotReturnUnrequestedHighEntropyFields() {
        var fallback: WebView? = null
        try {
            instrumentation.runOnMainSync {
                fallback = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.userAgentString = userAgent
                    WebViewCompat.addDocumentStartJavaScript(this,
                        requireNotNull(MobileUserAgent.navigatorIdentityScript(BrowserPlatform.WINDOWS, userAgent)), setOf("*"))
                    loadUrl(this@WindowsIdentityTest.url)
                }
            }
            val result = result()
            assertTrue("Fallback UA-CH object changes on every navigator read", result.getBoolean("sameObject"))
            checkRequestedFields(result)
        } finally { instrumentation.runOnMainSync { fallback?.destroy() } }
    }

    private fun result(): JSONObject {
        assertTrue("Browser identity request did not complete", received.await(15, TimeUnit.SECONDS))
        return requireNotNull(identity)
    }
    private fun checkRequestedFields(result: JSONObject) {
        val lowKeys = setOf("brands", "mobile", "platform")
        assertEquals(lowKeys, result.getJSONObject("low").keys().asSequence().toSet())
        assertEquals(lowKeys, result.getJSONObject("empty").keys().asSequence().toSet())
        assertEquals(lowKeys + "architecture", result.getJSONObject("architecture").keys().asSequence().toSet())
        assertEquals("x86", result.getJSONObject("full").getString("architecture"))
        assertEquals("64", result.getJSONObject("full").getString("bitness"))
    }
}

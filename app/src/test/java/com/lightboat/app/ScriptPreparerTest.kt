package com.lightboat.app

import com.lightboat.app.userscript.ScriptBundle
import com.lightboat.app.userscript.ScriptPreparer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ScriptPreparerTest {
    private val address = "https://scripts.example.test/main.user.js"

    private fun asset(text: String) = JSONObject().put("text", text)
        .put("base64", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        .put("mime", "text/plain")

    private fun prepare(source: String, load: (String, Boolean) -> Result<JSONObject>): Result<ScriptBundle> {
        var prepared: Result<ScriptBundle>? = null
        ScriptPreparer(ScriptPreparer.AssetLoader { url, _, _, resource, callback -> callback(load(url, resource)) })
            .prepare(source.trimIndent(), address, AppConfig.DEFAULT_USER_AGENT, BrowserPlatform.ANDROID) { prepared = it }
        return requireNotNull(prepared)
    }

    @Test fun failedNamedResourceDoesNotDiscardScriptOrPreventRemainingResources() {
        val requests = mutableListOf<String>()
        val result = prepare("""
            // ==UserScript==
            // @name Resource failure regression
            // @require ./library.js
            // @resource unavailable ./styles.css
            // @resource available ./icon.txt
            // ==/UserScript==
            window.scriptExecuted = true;
        """) { url, resource ->
            requests += url
            if (url.endsWith("styles.css")) Result.failure(IllegalStateException("脚本服务器返回 HTTP 418"))
            else Result.success(asset(if (resource) "retained resource" else "window.libraryLoaded = true;"))
        }
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val bundle = result.getOrThrow()
        assertEquals(3, requests.size)
        assertEquals(listOf("window.libraryLoaded = true;"), bundle.requirements)
        assertTrue(bundle.source.contains("window.scriptExecuted = true"))
        assertEquals("retained resource", bundle.resources.getJSONObject("available").getString("text"))
        val missing = bundle.resources.getJSONObject("unavailable")
        assertEquals("", missing.getString("text"))
        assertEquals("", missing.getString("base64"))
        assertTrue(missing.getString("error").contains("418"))
    }

    @Test fun failedRequiredLibraryStillPreventsExecution() {
        val requests = mutableListOf<String>()
        val result = prepare("""
            // ==UserScript==
            // @require ./library.js
            // @resource style ./styles.css
            // ==/UserScript==
        """) { url, _ ->
            requests += url
            Result.failure(IllegalStateException("HTTP 503"))
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("503"))
        assertEquals(listOf("https://scripts.example.test/library.js"), requests)
    }

    @Test fun integrityMismatchRemainsFatalForNamedResources() {
        val result = prepare("""
            // ==UserScript==
            // @resource style ./styles.css#sha256=invalid
            // ==/UserScript==
        """) { _, _ -> Result.success(asset("untrusted content")) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("完整性"))
    }
}

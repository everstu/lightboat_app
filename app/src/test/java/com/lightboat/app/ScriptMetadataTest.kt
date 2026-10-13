package com.lightboat.app

import com.lightboat.app.userscript.ScriptMetadata
import org.junit.Assert.*
import org.junit.Test

class ScriptMetadataTest {
    private fun script(lines: String) = "// ==UserScript==\n$lines\n// ==/UserScript==\n(function(){})();"

    @Test fun recognizesDefaultIdleAndPlainJavaScriptStart() {
        assertEquals("document-idle", ScriptMetadata.parse(script("// @name Example\n// @grant none")).runAt)
        assertEquals("document-start", ScriptMetadata.parse("window.ready=true;").runAt)
    }

    @Test fun parsesMultipleDirectivesAndCrLfNoFrames() {
        val meta = ScriptMetadata.parse(script("// @name Example\n// @match https://example.com/*\n// @match https://other.com/*\n// @noframes\n// @grant GM_getValue").replace("\n", "\r\n"))
        assertEquals(2, meta.all("match").size)
        assertTrue(meta.noFrames)
        assertTrue(meta.permits("GM_getValue"))
        assertFalse(meta.permits("GM_setValue"))
    }

    @Test fun connectsUseDomainBoundariesAndCheckProtocol() {
        val meta = ScriptMetadata.parse(script("// @connect example.com\n// @connect self\n// @connect 127.0.0.1"))
        assertTrue(meta.permitsConnection("https://example.com/api", "https://site.org"))
        assertTrue(meta.permitsConnection("https://a.example.com/api", "https://site.org"))
        assertFalse(meta.permitsConnection("https://evil-example.com/api", "https://site.org"))
        assertFalse(meta.permitsConnection("file:///tmp/a", "https://site.org"))
        assertFalse(meta.permitsConnection("https://user:pass@example.com/api", "https://site.org"))
        assertTrue(meta.permitsConnection("http://127.0.0.1:8080/api", "https://site.org"))
    }

    @Test fun noConnectAllowsOnlySameOrigin() {
        val meta = ScriptMetadata.parse(script("// @grant GM_xmlhttpRequest"))
        assertTrue(meta.permitsConnection("https://site.org/api", "https://site.org/page"))
        assertFalse(meta.permitsConnection("http://site.org/api", "https://site.org/page"))
        assertFalse(meta.permitsConnection("https://site.org:8443/api", "https://site.org/page"))
    }

    @Test fun rejectsUnsupportedRunAtAndExcessiveDependencies() {
        assertThrows(IllegalArgumentException::class.java) { ScriptMetadata.parse(script("// @run-at nowhere")) }
        assertThrows(IllegalArgumentException::class.java) { ScriptMetadata.parse(script((1..17).joinToString("\n") { "// @require https://example.com/$it.js" })) }
    }

    @Test fun nativeAndJsMatchRulesUseSameBoundariesAndExclusions() {
        val meta = ScriptMetadata.parse(script("// @match *://*.example.com/watch*\n// @exclude *private*"))
        assertTrue(meta.matchesPage("https://example.com/watch?a=1"))
        assertTrue(meta.matchesPage("http://a.example.com/watch"))
        assertFalse(meta.matchesPage("https://evil-example.com/watch"))
        assertFalse(meta.matchesPage("https://example.com/watch/private"))
        assertFalse(meta.matchesPage("https://elsewhere.com/?https://example.com/watch"))
    }

    @Test fun redirectOriginIncludesSchemeAndEffectivePort() {
        assertTrue(ScriptMetadata.sameOrigin("https://example.com/api", "https://example.com:443/next"))
        assertFalse(ScriptMetadata.sameOrigin("https://example.com/api", "http://example.com/api"))
        assertFalse(ScriptMetadata.sameOrigin("https://example.com/api", "https://example.com:8443/api"))
    }
}

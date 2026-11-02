package com.lightboat.app

import org.junit.Assert.*
import org.junit.Test

class DebugLogFilterTest {
    private fun entry(source: String, message: String) = DebugLogEntry("2026-10-17 00:12:34.567 [$source] $message")

    @Test fun combinesKeywordExactSourceAndLevelWithoutChangingTheLog() {
        val logs = listOf(entry("gm-xhr", "error url=https://rules.example/14.txt GM 请求响应不能超过 4 MiB"),
            entry("userscript", "ERROR GM request: https://rules.example/14.txt"),
            entry("gm-xhr", "load status=200 error= url=https://rules.example/14.txt"))
        val filter = DebugLogFilter(" RULES.EXAMPLE/14.TXT ", "gm-xhr", DebugLogLevel.ERROR)
        assertEquals(listOf(logs.first()), logs.filter(filter::matches))
        assertEquals(3, logs.filter(DebugLogFilter()::matches).size)
        assertTrue(logs.first().raw.endsWith("GM 请求响应不能超过 4 MiB"))
        assertTrue(DebugLogFilter("不能超过").matches(logs.first()))
        assertFalse(DebugLogFilter(source = "xhr").matches(logs.first()))
    }

    @Test fun identifiesFailuresWithoutMatchingSuccessfulEmptyErrorFields() {
        listOf(entry("page-error", "net::ERR_FAILED"), entry("previous-crash", "OutOfMemoryError"),
            entry("gm-xhr", "event=error status=0"), entry("gm-xhr", "event=load status=200 ok=false"),
            entry("script", "load result=failure"), entry("gm-xhr", "response status=418"),
            entry("userscript", "ERROR TypeError: documentElement is null")).forEach {
            assertEquals(it.raw, DebugLogLevel.ERROR, it.level)
        }
        assertEquals(DebugLogLevel.INFO, entry("gm-xhr", "event=load status=200 ok=true error=").level)
        assertEquals(DebugLogLevel.INFO, entry("network", "failed=false error=").level)
        assertEquals(DebugLogLevel.WARNING, entry("userscript", "warning resource unavailable").level)
        assertEquals(DebugLogLevel.WARNING, entry("gm-xhr", "timeout url=https://example.com").level)
    }

    @Test fun keepsTrimMarkersAndMatchesLiteralKeywords() {
        val marker = DebugLogEntry("--- log trimmed ---")
        assertEquals("", marker.time)
        assertEquals("app", marker.source)
        assertEquals(marker.raw, marker.message)
        assertTrue(DebugLogFilter().matches(marker))
        assertTrue(DebugLogFilter("[gm-xhr]").matches(entry("gm-xhr", "load status=200")))
        assertFalse(DebugLogFilter(".*").matches(marker))
        assertFalse(DebugLogFilter("  ").active)
    }
}

package com.lightboat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogTest {
    @Test fun safeUrlKeepsPathAndQueryNamesButRedactsValues() {
        assertEquals(
            "https://cdn.example/api/videos/meta?ep=<redacted>&token=<redacted>",
            DebugLog.safeUrl("https://cdn.example/api/videos/meta?ep=1&token=secret")
        )
    }

    @Test fun headerSummaryNeverIncludesHeaderValues() {
        assertEquals("range,authorization,cookie", DebugLog.headersPresent(mapOf(
            "Range" to "bytes=0-10",
            "Authorization" to "Bearer secret",
            "Cookie" to "sid=secret",
        )))
    }
}

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

}

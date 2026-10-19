package com.lightboat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {
    @Test fun safeUrlKeepsPathAndQueryNamesButRedactsValues() {
        assertEquals(
            "https://cdn.example/api/videos/meta?ep=<redacted>&token=<redacted>",
            DebugLog.safeUrl("https://cdn.example/api/videos/meta?ep=1&token=secret")
        )
    }

    @Test fun sanitizeRemovesCompleteAuthorizationAndCookieHeaders() {
        val inputs = listOf(
            "Authorization: Bearer auth-secret",
            "Authorization: Basic basic-secret",
            "Cookie: first=cookie-secret; second=second-secret",
            "Set-Cookie: session=session-secret; Path=/; HttpOnly",
            "Proxy-Authorization: Bearer proxy-secret",
            """{"Authorization":"Bearer json-secret","Cookie":"a=one-secret; b=two-secret"}"""
        )
        inputs.forEach { input ->
            val clean = DebugLog.sanitize(input)
            assertFalse("Secret survived sanitization: $input -> $clean", clean.contains("secret"))
            assertTrue(clean.contains("<redacted>"))
        }
    }

    @Test fun sanitizePreservesOrdinaryDiagnosticsAndRedactsMultilineHeaders() {
        val clean = DebugLog.sanitize("status=401 hasCookie=true\nAuthorization: Bearer secret-token\n" +
            "Cookie: a=secret-a; b=secret-b\nurl=https://example.com/api?token=secret-query")
        assertTrue(clean.contains("status=401 hasCookie=true"))
        assertTrue(clean.contains("https://example.com/api?token=<redacted>"))
        assertFalse(clean.contains("secret"))
    }

}

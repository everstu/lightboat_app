package com.lightboat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileUserAgentTest {
    @Test
    fun windowsChromeKeepsInstalledChromiumVersion() {
        val android = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.7339.51 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.7339.51 Safari/537.36",
            MobileUserAgent.windowsChrome(android),
        )
        val metadata = MobileUserAgent.windowsMetadata(MobileUserAgent.windowsChrome(android))
        assertEquals("Windows", metadata.platform)
        assertEquals("10.0.0", metadata.platformVersion)
        assertEquals("x86", metadata.architecture)
        assertEquals(64, metadata.bitness)
        assertEquals(false, metadata.isMobile)
        assertEquals("140.0.7339.51", metadata.fullVersion)
    }
}

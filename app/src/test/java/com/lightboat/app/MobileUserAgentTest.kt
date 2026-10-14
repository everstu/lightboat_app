package com.lightboat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(BrowserPlatform.WINDOWS, MobileUserAgent.detect(MobileUserAgent.windowsChrome(android)))
    }

    @Test
    fun applePresetRemovesAndroidIdentityValues() {
        val userAgent = MobileUserAgent.appleSafari()
        val metadata = MobileUserAgent.appleMetadata()

        assertEquals(BrowserPlatform.APPLE, MobileUserAgent.detect(userAgent))
        assertEquals("iOS", metadata.platform)
        assertEquals("iPhone", metadata.model)
        assertEquals(true, metadata.isMobile)
        val script = MobileUserAgent.navigatorIdentityScript(BrowserPlatform.APPLE, userAgent).orEmpty()
        assertTrue(script.contains("platform:'iPhone'"))
        assertTrue(script.contains("vendor:'Apple Computer, Inc.'"))
        assertTrue(script.contains("'userAgentData'"))
        assertTrue(script.contains("get: () => undefined"))
        assertEquals(null, MobileUserAgent.navigatorIdentityScript(BrowserPlatform.ANDROID, userAgent))
    }
}

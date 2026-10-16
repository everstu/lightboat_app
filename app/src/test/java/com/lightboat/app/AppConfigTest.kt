package com.lightboat.app

import org.junit.Assert.*
import org.junit.Test

class AppConfigTest {
    private val first = Profile("first", "工作", "https://example.com", "https://example.com/a.js")
    private val second = Profile("second", "测试", "http://localhost:8080", "http://localhost:8080/b.js")

    @Test fun firstProfileIsStartupProfile() {
        assertEquals(first, AppConfig.EMPTY.upsert(first).lastProfile)
    }

    @Test fun selectionPersistsAcrossJsonRoundTrip() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second)
        assertEquals(first, config.lastProfile)
        val edited = second.copy(url = "https://new.example.com")
        val switched = config.upsert(edited).select("second")
        assertEquals(edited, ConfigCodec.decode(ConfigCodec.encode(switched)).lastProfile)
        assertThrows(IllegalArgumentException::class.java) { config.select("missing") }
    }

    @Test fun deletionRetainsValidSelectionOrClearsItWhenEmpty() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second)
        assertEquals(second, config.remove("first").lastProfile)
        assertEquals(AppConfig.EMPTY, config.remove("first").remove("second"))
        assertEquals(first, config.remove("second").lastProfile)
    }

    @Test fun urlsKeepQueriesAndSupportHttp() {
        assertEquals("https://example.com/a?q=1#x", AppConfig.normalizeUrl(" https://example.com/a?q=1#x "))
        assertEquals("http://127.0.0.1:8080", AppConfig.normalizeUrl("http://127.0.0.1:8080"))
    }

    @Test fun unicodeUrlLimitsApplyAfterEncodingSoSavedProfilesRemainReadable() {
        val short = AppConfig.normalizeUrl("https://example.com/路径?q=测试#片段")
        val config = AppConfig.EMPTY.upsert(first.copy(url = short))
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
        assertEquals(short, AppConfig.normalizeUrl(short))
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.normalizeUrl("https://example.com/" + "路".repeat(1000))
        }
    }

    @Test fun rejectsInvalidOrUnsupportedUrls() {
        listOf("", "example.com", "javascript:alert(1)", "file:///tmp/a", "https://",
            "https://user:pass@example.com", "https://example.com:65536", "https://bad host/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { AppConfig.normalizeUrl(it) }
        }
    }

    @Test fun emptyConfigRoundTrips() {
        assertEquals(AppConfig.EMPTY, ConfigCodec.decode(ConfigCodec.encode(AppConfig.EMPTY)))
    }

    @Test fun profilesWithoutScriptsRoundTrip() {
        val profile = first.copy(scriptUrl = "")
        val config = AppConfig.EMPTY.upsert(profile)
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
    }

    @Test fun missingNullOrBlankScriptAddressMeansNoInjectedScript() {
        listOf("", ",\"scriptUrl\":null", ",\"scriptUrl\":\"\"", ",\"scriptUrl\":\"   \"").forEach { optionalField ->
            val json = """{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"普通网页","url":"https://example.com"$optionalField}]}"""
            assertEquals("", ConfigCodec.decode(json).lastProfile?.scriptUrl)
        }
    }

    @Test fun aNonEmptyScriptAddressStillRequiresAValidHttpUrl() {
        listOf("javascript:alert(1)", "file:///tmp/script.js", "not-a-url").forEach { script ->
            val config = AppConfig.EMPTY.upsert(first.copy(scriptUrl = script))
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(ConfigCodec.encode(config)) }
        }
    }

    @Test fun customUserAgentRoundTripsWithoutChangingSelection() {
        val profile = first.copy(userAgent = "Custom Android Mobile/1.0", platform = BrowserPlatform.ANDROID)
        val config = AppConfig.EMPTY.upsert(profile)
        assertEquals(profile, ConfigCodec.decode(ConfigCodec.encode(config)).lastProfile)
    }

    @Test fun platformPresetRoundTripsAndLegacyConfigInfersItFromUserAgent() {
        val apple = first.copy(userAgent = MobileUserAgent.appleSafari(), platform = BrowserPlatform.APPLE)
        assertEquals(BrowserPlatform.APPLE,
            ConfigCodec.decode(ConfigCodec.encode(AppConfig.EMPTY.upsert(apple))).lastProfile?.platform)
        val legacy = """{"version":1,"lastProfileId":"w","profiles":[{"id":"w","name":"Windows","url":"https://example.com","scriptUrl":"https://example.com/a.js","userAgent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0"}]}"""
        assertEquals(BrowserPlatform.WINDOWS, ConfigCodec.decode(legacy).lastProfile?.platform)
    }

    @Test fun oldConfigUsesProvidedDefaultUserAgent() {
        val text = """{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"应用","url":"https://example.com","scriptUrl":"https://example.com/a.js"}]}"""
        assertEquals("Device Android Mobile/1.0", ConfigCodec.decode(text, "Device Android Mobile/1.0").lastProfile?.userAgent)
        assertEquals(AppConfig.DEFAULT_USER_AGENT, ConfigCodec.decode(text).lastProfile?.userAgent)
    }

    @Test fun rejectsEmptyNonAsciiAndHeaderInjectionUserAgents() {
        listOf("", "   ", "Agent\r\nX-Header: true", "\nAgent", "Agent\tTest", "中文 UA", "x".repeat(2049)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppConfig.normalizeUserAgent(it) }
        }
        assertEquals("Android Mobile/1.0", AppConfig.normalizeUserAgent(" Android Mobile/1.0 "))
    }

    @Test fun malformedImportsAreRejectedWithoutGuessingSelection() {
        val valid = ConfigCodec.encode(AppConfig.EMPTY.upsert(first))
        assertThrows(Exception::class.java) { ConfigCodec.decode(valid.replace("\"version\": 1", "\"version\": 2")) }
        assertThrows(Exception::class.java) { ConfigCodec.decode(valid.replace("\"lastProfileId\": \"first\"", "\"lastProfileId\": \"missing\"")) }
        assertThrows(Exception::class.java) { ConfigCodec.decode("""{"version":1,"lastProfileId":null,"profiles":[{"id":"x","name":"X","url":"file:///a","scriptUrl":"https://example.com/a.js"}]}""") }
        assertThrows(Exception::class.java) { ConfigCodec.decode(valid.replace("\"name\": \"工作\"", "\"name\": \"\"")) }
        val duplicate = """{"version":1,"lastProfileId":"x","profiles":[{"id":"x","name":"a","url":"https://example.com","scriptUrl":"https://example.com/a.js"},{"id":"x","name":"b","url":"https://example.com","scriptUrl":"https://example.com/b.js"}]}"""
        assertThrows(Exception::class.java) { ConfigCodec.decode(duplicate) }
    }

    @Test fun reorderingPreservesValuesAndLastSelectionAcrossJsonRoundTrip() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second).select(first.id)
        val reordered = config.reorder(listOf(second.id, first.id))
        assertEquals(listOf(second, first), reordered.profiles)
        assertEquals(first, reordered.lastProfile)
        assertEquals(reordered, ConfigCodec.decode(ConfigCodec.encode(reordered)))
        assertEquals(config, reordered.reorder(listOf(first.id, second.id)))
    }

    @Test fun reorderingCannotDropDuplicateOrInventProfiles() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second)
        listOf(listOf(first.id), listOf(first.id, first.id), listOf(first.id, "missing")).forEach { ids ->
            assertThrows(IllegalArgumentException::class.java) { config.reorder(ids) }
        }
        assertEquals(AppConfig.EMPTY, AppConfig.EMPTY.reorder(emptyList()))
    }
}

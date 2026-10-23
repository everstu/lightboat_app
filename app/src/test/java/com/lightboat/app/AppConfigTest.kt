package com.lightboat.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppConfigTest {
    private val first = Profile("first", "工作", "https://example.com")
    private val second = Profile("second", "测试", "http://localhost:8080")

    @Test fun firstProfileIsStartupProfile() {
        assertEquals(first, AppConfig.EMPTY.upsert(first).defaultProfile)
    }

    @Test fun selectionPersistsAcrossJsonRoundTrip() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second)
        assertEquals(first, config.defaultProfile)
        val edited = second.copy(url = "https://new.example.com")
        val switched = config.upsert(edited).setDefault("second")
        assertEquals(edited, ConfigCodec.decode(ConfigCodec.encode(switched)).defaultProfile)
        assertThrows(IllegalArgumentException::class.java) { config.setDefault("missing") }
    }

    @Test fun defaultProfileChangesOnlyExplicitlyAndKeepsTheCompatibleJsonKey() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second)
        val chosen = config.setDefault("second")
        assertEquals(config.profiles, chosen.profiles)
        // Editing, reordering and favoriting other profiles never move the default.
        assertEquals("second", chosen.upsert(first.copy(name = "改名")).moveToTop("first").toggleFavorite("first").defaultProfileId)
        val json = JSONObject(ConfigCodec.encode(chosen))
        assertEquals("second", json.getString("lastProfileId"))
        assertFalse(json.has("defaultProfileId"))
        // Backups from earlier versions keep their last opened profile as the default.
        val legacy = """{"version":1,"lastProfileId":"b","profiles":[{"id":"a","name":"A","url":"https://a.example"},{"id":"b","name":"B","url":"https://b.example"}]}"""
        assertEquals("B", ConfigCodec.decode(legacy).defaultProfile?.name)
    }

    @Test fun deletionRetainsValidSelectionOrClearsItWhenEmpty() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second)
        assertEquals(second, config.remove("first").defaultProfile)
        assertEquals(AppConfig.EMPTY, config.remove("first").remove("second"))
        assertEquals(first, config.remove("second").defaultProfile)
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
        val profile = first.copy(scripts = emptyList())
        val config = AppConfig.EMPTY.upsert(profile)
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
    }

    @Test fun missingNullOrBlankScriptAddressMeansNoInjectedScript() {
        listOf("", ",\"scriptUrl\":null", ",\"scriptUrl\":\"\"", ",\"scriptUrl\":\"   \"").forEach { optionalField ->
            val json = """{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"普通网页","url":"https://example.com"$optionalField}]}"""
            assertTrue(ConfigCodec.decode(json).defaultProfile!!.scripts.isEmpty())
        }
    }

    @Test fun aNonEmptyScriptAddressStillRequiresAValidHttpUrl() {
        listOf("javascript:alert(1)", "file:///tmp/script.js", "not-a-url").forEach { script ->
            val text = """{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"a","url":"https://example.com","scriptUrl":"$script"}]}"""
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(text) }
        }
    }

    @Test fun customUserAgentRoundTripsWithoutChangingSelection() {
        val profile = first.copy(userAgent = "Custom Android Mobile/1.0", platform = BrowserPlatform.ANDROID)
        val config = AppConfig.EMPTY.upsert(profile)
        assertEquals(profile, ConfigCodec.decode(ConfigCodec.encode(config)).defaultProfile)
    }

    @Test fun platformPresetRoundTripsAndLegacyConfigInfersItFromUserAgent() {
        val apple = first.copy(userAgent = MobileUserAgent.appleSafari(), platform = BrowserPlatform.APPLE)
        assertEquals(BrowserPlatform.APPLE,
            ConfigCodec.decode(ConfigCodec.encode(AppConfig.EMPTY.upsert(apple))).defaultProfile?.platform)
        val legacy = """{"version":1,"lastProfileId":"w","profiles":[{"id":"w","name":"Windows","url":"https://example.com","scriptUrl":"https://example.com/a.js","userAgent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0"}]}"""
        assertEquals(BrowserPlatform.WINDOWS, ConfigCodec.decode(legacy).defaultProfile?.platform)
    }

    @Test fun oldConfigUsesProvidedDefaultUserAgent() {
        val text = """{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"应用","url":"https://example.com","scriptUrl":"https://example.com/a.js"}]}"""
        assertEquals("Device Android Mobile/1.0", ConfigCodec.decode(text, "Device Android Mobile/1.0").defaultProfile?.userAgent)
        assertEquals(AppConfig.DEFAULT_USER_AGENT, ConfigCodec.decode(text).defaultProfile?.userAgent)
    }

    @Test fun rejectsEmptyNonAsciiAndHeaderInjectionUserAgents() {
        listOf("", "   ", "Agent\r\nX-Header: true", "\nAgent", "Agent\tTest", "中文 UA", "x".repeat(2049)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppConfig.normalizeUserAgent(it) }
        }
        assertEquals("Android Mobile/1.0", AppConfig.normalizeUserAgent(" Android Mobile/1.0 "))
    }

    @Test fun malformedImportsAreRejectedWithoutGuessingSelection() {
        val valid = ConfigCodec.encode(AppConfig.EMPTY.upsert(first))
        assertThrows(Exception::class.java) { ConfigCodec.decode(valid.replace("\"version\": 2", "\"version\": 3")) }
        assertThrows(Exception::class.java) { ConfigCodec.decode(valid.replace("\"lastProfileId\": \"first\"", "\"lastProfileId\": \"missing\"")) }
        assertThrows(Exception::class.java) { ConfigCodec.decode("""{"version":1,"lastProfileId":null,"profiles":[{"id":"x","name":"X","url":"file:///a","scriptUrl":"https://example.com/a.js"}]}""") }
        assertThrows(Exception::class.java) { ConfigCodec.decode(valid.replace("\"name\": \"工作\"", "\"name\": \"\"")) }
        val duplicate = """{"version":1,"lastProfileId":"x","profiles":[{"id":"x","name":"a","url":"https://example.com","scriptUrl":"https://example.com/a.js"},{"id":"x","name":"b","url":"https://example.com","scriptUrl":"https://example.com/b.js"}]}"""
        assertThrows(Exception::class.java) { ConfigCodec.decode(duplicate) }
    }

    @Test fun reorderingPreservesValuesAndLastSelectionAcrossJsonRoundTrip() {
        val config = AppConfig.EMPTY.upsert(first).upsert(second).setDefault(first.id)
        val reordered = config.reorder(listOf(second.id, first.id))
        assertEquals(listOf(second, first), reordered.profiles)
        assertEquals(first, reordered.defaultProfile)
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

    @Test fun profileNotesRoundTripExactlyAndDoNotChangeOtherFields() {
        val note = "  中文说明 😀\n第二行\t缩进   \r\n<b>文本</b> & <script>  "
        val profile = first.copy(note = note)
        val config = AppConfig.EMPTY.upsert(profile)
        val decoded = ConfigCodec.decode(ConfigCodec.encode(config)).defaultProfile!!
        assertEquals(note, decoded.note)
        assertEquals(profile.copy(note = ""), decoded.copy(note = ""))
        assertEquals(note, JSONObject(ConfigCodec.encode(config)).getJSONArray("profiles")
            .getJSONObject(0).getString("note"))
    }

    @Test fun emptyNotesAreWrittenAndLegacyMissingOrNullNotesReadAsEmpty() {
        val encoded = JSONObject(ConfigCodec.encode(AppConfig.EMPTY.upsert(first)))
        val item = encoded.getJSONArray("profiles").getJSONObject(0)
        assertTrue(item.has("note"))
        assertEquals("", item.getString("note"))
        assertEquals("", ConfigCodec.decode("""{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"旧配置","url":"https://example.com"}]}""").defaultProfile?.note)

        fun variant(change: JSONObject.() -> Unit): String = JSONObject(encoded.toString()).let { root ->
            root.getJSONArray("profiles").getJSONObject(0).change()
            root.toString()
        }
        assertEquals("", ConfigCodec.decode(variant { remove("note") }).defaultProfile?.note)
        assertEquals("", ConfigCodec.decode(variant { put("note", JSONObject.NULL) }).defaultProfile?.note)
        assertEquals("", ConfigCodec.decode(variant { put("note", "  \n\t\r ") }).defaultProfile?.note)
    }

    @Test fun nonStringNotesAreRejectedInsteadOfBeingConverted() {
        val encoded = JSONObject(ConfigCodec.encode(AppConfig.EMPTY.upsert(first)))
        listOf(42, 1.5, true, JSONObject().put("text", "备注"), org.json.JSONArray().put("备注")).forEach { value ->
            val root = JSONObject(encoded.toString())
            root.getJSONArray("profiles").getJSONObject(0).put("note", value)
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(root.toString()) }
        }
    }

    @Test fun noteLimitCountsUtf16CodeUnitsAndRejectsImportInsteadOfTruncating() {
        assertEquals("x".repeat(AppConfig.MAX_PROFILE_NOTE_LENGTH),
            AppConfig.normalizeProfileNote("x".repeat(AppConfig.MAX_PROFILE_NOTE_LENGTH)))
        assertEquals("😀".repeat(1000), AppConfig.normalizeProfileNote("😀".repeat(1000)))
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.normalizeProfileNote("x".repeat(AppConfig.MAX_PROFILE_NOTE_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.normalizeProfileNote("😀".repeat(1001))
        }

        val encoded = JSONObject(ConfigCodec.encode(AppConfig.EMPTY.upsert(first)))
        val root = JSONObject(encoded.toString())
        root.getJSONArray("profiles").getJSONObject(0).put("note", "x".repeat(AppConfig.MAX_PROFILE_NOTE_LENGTH + 1))
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(root.toString()) }
    }

    @Test fun noteTextRulesAllowLinesTabsAndEmojiButRejectOtherIsoControls() {
        val allowed = "第一行\n第二行\r\n\t缩进 😀 <b>文本</b>"
        assertEquals(allowed, AppConfig.normalizeProfileNote(allowed))
        assertEquals("", AppConfig.normalizeProfileNote(" \n\t\r  "))
        assertEquals("  实际内容  ", AppConfig.normalizeProfileNote("  实际内容  "))
        listOf("\u0000", "a\u0001b", "\u000B", "\u000C", "\u007F", "\u0085").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { AppConfig.normalizeProfileNote(invalid) }
        }

        val encoded = JSONObject(ConfigCodec.encode(AppConfig.EMPTY.upsert(first)))
        val root = JSONObject(encoded.toString())
        root.getJSONArray("profiles").getJSONObject(0).put("note", "有效\u0000内容")
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(root.toString()) }
    }
}

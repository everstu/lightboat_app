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

    @Test fun rejectsInvalidOrUnsupportedUrls() {
        listOf("", "example.com", "javascript:alert(1)", "file:///tmp/a", "https://",
            "https://user:pass@example.com", "https://example.com:65536", "https://bad host/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { AppConfig.normalizeUrl(it) }
        }
    }

    @Test fun emptyConfigRoundTrips() {
        assertEquals(AppConfig.EMPTY, ConfigCodec.decode(ConfigCodec.encode(AppConfig.EMPTY)))
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
}

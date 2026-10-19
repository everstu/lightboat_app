package com.lightboat.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScriptLibraryTest {
    private val first = SharedScript("a", "广告过滤", "https://scripts.example/a.js")
    private val second = SharedScript("b", "阅读辅助", "https://scripts.example/b.js")
    private val config = AppConfig.EMPTY.upsertScript(first).upsertScript(second)
        .upsert(Profile("p1", "页面一", "https://one.example", listOf(ProfileScript("b", false), ProfileScript("a"))))
        .upsert(Profile("p2", "页面二", "https://two.example", listOf(ProfileScript("a"))))

    @Test fun sharedAddressUpdateImmediatelyResolvesForEveryProfileAndRetainsSelections() {
        val changed = config.upsertScript(first.copy(url = "https://new.example/ad.js", name = "去广告"))
        assertEquals(config.profiles, changed.profiles)
        assertEquals(listOf("https://new.example/ad.js"), changed.activeScripts(changed.profiles[0]).map { it.url })
        assertEquals(changed.activeScripts(changed.profiles[0]), changed.activeScripts(changed.profiles[1]))
        assertEquals(config.lastProfileId, changed.lastProfileId)
        assertEquals(changed, ConfigCodec.decode(ConfigCodec.encode(changed)))
    }

    @Test fun referencesKeepOrderDisabledEntriesAndUnreferencedLibraryOnExport() {
        val value = config.upsertScript(SharedScript("unused", "未使用", "https://scripts.example/unused.js"))
        assertEquals(value, ConfigCodec.decode(ConfigCodec.encode(value)))
        assertEquals(listOf("b", "a"), value.profiles.first().scripts.map { it.scriptId })
        assertEquals(listOf("a"), value.activeScripts(value.profiles.first()).map { it.id })
        val reordered = value.upsert(value.profiles.first().copy(scripts = listOf(ProfileScript("a"), ProfileScript("b"))))
        assertEquals(listOf("a", "b"), reordered.activeScripts(reordered.profiles.first()).map { it.id })
    }

    @Test fun legacySingleScriptImportsDeduplicateUrlsAndPreserveOldStorageIdentity() {
        val legacy = """{"version":1,"lastProfileId":"p1","profiles":[
            {"id":"p1","name":"a","url":"https://one.example","scriptUrl":"https://scripts.example/a.js"},
            {"id":"p2","name":"b","url":"https://two.example","scriptUrl":" https://scripts.example/a.js "},
            {"id":"p3","name":"c","url":"https://three.example"}]}"""
        val migrated = ConfigCodec.decode(legacy)
        assertEquals(1, migrated.scriptLibrary.size)
        assertEquals(migrated.profiles[0].scripts, migrated.profiles[1].scripts)
        assertTrue(migrated.profiles[0].scripts.single().legacyStorage)
        assertTrue(migrated.profiles[2].scripts.isEmpty())
        assertEquals(migrated, ConfigCodec.decode(ConfigCodec.encode(migrated)))
    }

    @Test fun removingSharedScriptCleansAllReferencesWithoutChangingProfileSelectionOrOrder() {
        val removed = config.removeScript("a")
        assertEquals(listOf(second), removed.scriptLibrary)
        assertEquals(listOf(ProfileScript("b", false)), removed.profiles[0].scripts)
        assertTrue(removed.profiles[1].scripts.isEmpty())
        assertEquals(config.lastProfileId, removed.lastProfileId)
        assertEquals(config.profiles.map { it.id }, removed.profiles.map { it.id })
        assertEquals(removed, ConfigCodec.decode(ConfigCodec.encode(removed)))
    }

    @Test fun duplicateAddressesDuplicateReferencesAndMissingScriptIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { config.upsertScript(first.copy(id = "duplicate")) }
        assertThrows(IllegalArgumentException::class.java) { config.upsert(config.profiles.first().copy(scripts = listOf(ProfileScript("missing")))) }
        assertThrows(IllegalArgumentException::class.java) { config.upsert(config.profiles.first().copy(scripts = listOf(ProfileScript("a"), ProfileScript("a")))) }
        listOf(
            JSONArray().put(JSONObject().put("scriptId", "missing")),
            JSONArray().put(JSONObject().put("scriptId", "a").put("enabled", "true")),
            JSONArray().put(JSONObject().put("scriptId", "a")).put(JSONObject().put("scriptId", "a"))
        ).forEach { refs ->
            val root = JSONObject(ConfigCodec.encode(config))
            root.getJSONArray("profiles").getJSONObject(0).put("scripts", refs)
            assertThrows(Exception::class.java) { ConfigCodec.decode(root.toString()) }
        }
    }

    @Test fun equivalentHostsSchemesAndDefaultPortsReuseTheSameAddressButPathsAndQueriesRemainDistinct() {
        assertEquals(first.url, AppConfig.normalizeScriptUrl(" HTTPS://SCRIPTS.EXAMPLE:443/a.js "))
        assertThrows(IllegalArgumentException::class.java) { config.upsertScript(first.copy(id = "other", url = "HTTPS://SCRIPTS.EXAMPLE:443/a.js")) }
        val next = config.upsertScript(SharedScript("q", "参数版本", first.url + "?v=2"))
        assertEquals(3, next.scriptLibrary.size)
    }

    @Test fun blankNamesUseDecodedFilenamesWithoutQueryAndKeepTheSharedId() {
        val added = AppConfig.EMPTY.upsertScript(SharedScript("file", "  ", "https://scripts.example/%E9%98%85%E8%AF%BB.user.js?v=1#hash"))
        assertEquals("阅读.user.js", added.scriptLibrary.single().name)
        val changed = added.upsertScript(added.scriptLibrary.single().copy(name = "", url = "https://scripts.example/new.js"))
        assertEquals("file", changed.scriptLibrary.single().id)
        assertEquals("new.js", changed.scriptLibrary.single().name)
        assertEquals("脚本", AppConfig.defaultScriptName("https://scripts.example/?download=1"))
        assertEquals(changed, ConfigCodec.decode(ConfigCodec.encode(changed)))
    }

    @Test fun jsonAcceptsMissingNullAndBlankNamesButRejectsWrongTypes() {
        for (name in listOf<Any?>(null, JSONObject.NULL, " ")) {
            val root = JSONObject(ConfigCodec.encode(config))
            val script = root.getJSONArray("scriptLibrary").getJSONObject(0)
            if (name == null) script.remove("name") else script.put("name", name)
            assertEquals("a.js", ConfigCodec.decode(root.toString()).scriptLibrary.first().name)
        }
        val invalid = JSONObject(ConfigCodec.encode(config))
        invalid.getJSONArray("scriptLibrary").getJSONObject(0).put("name", 123)
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(invalid.toString()) }
    }
}

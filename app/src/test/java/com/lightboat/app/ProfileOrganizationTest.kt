package com.lightboat.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProfileOrganizationTest {
    private fun profile(id: String, favorite: Boolean = false, categoryId: String? = null) =
        Profile(id, "配置$id", "https://example.com/$id", "", categoryId = categoryId, favorite = favorite)

    private val grouped = AppConfig("b", listOf(
        profile("a", true, "work"), profile("b"), profile("c", true, "work"),
        profile("d", false, "work"), profile("e", true, "play")
    ), linkedMapOf("work" to "工作", "play" to "娱乐"))

    @Test fun legacyConfigurationDefaultsToUncategorizedAndNotFavorite() {
        val legacy = """{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"旧配置","url":"https://example.com"}]}"""
        val config = ConfigCodec.decode(legacy)
        assertTrue(config.categories.isEmpty())
        assertNull(config.lastProfile?.categoryId)
        assertFalse(config.lastProfile!!.favorite)
    }

    @Test fun inlineCategoryCreationUsesAnIndependentIdAndReusesExistingNames() {
        val first = AppConfig.EMPTY.upsert(profile("a"), "  Work  ")
        val id = first.lastProfile!!.categoryId!!
        assertEquals(id, UUID.fromString(id).toString())
        assertNotEquals("Work", id)
        assertEquals(mapOf(id to "Work"), first.categories)
        val second = first.upsert(profile("b"), "work")
        assertEquals(id, second.profiles.last().categoryId)
        assertEquals(first.categories, second.categories)
        assertTrue(AppConfig.EMPTY.categories.isEmpty())
    }

    @Test fun editingCategoryOnlyMovesTheEditedProfileAndBlankClearsItsAssociation() {
        val original = grouped.profiles.first()
        val moved = grouped.upsert(original, "新分类")
        assertNotEquals(original.categoryId, moved.profiles.first().categoryId)
        assertEquals("work", moved.profiles[2].categoryId)
        assertEquals("工作", moved.categories["work"])
        val cleared = moved.upsert(moved.profiles.first(), "  ")
        assertNull(cleared.profiles.first().categoryId)
        assertTrue(cleared.profiles.first().favorite)
        assertEquals(moved.categories, cleared.categories)
        assertEquals(grouped.lastProfileId, cleared.lastProfileId)
    }

    @Test fun exportKeepsTheCategoryMapFavoritesGlobalOrderAndSelection() {
        val config = grouped.toggleFavorite("b").moveToTop("e")
        val json = ConfigCodec.encode(config)
        assertEquals(config, ConfigCodec.decode(json))
        val root = JSONObject(json)
        assertEquals("工作", root.getJSONObject("categories").getString("work"))
        assertFalse(root.getJSONObject("categories").has("工作"))
        assertEquals("play", root.getJSONArray("profiles").getJSONObject(0).getString("categoryId"))
        assertTrue(root.getJSONArray("profiles").getJSONObject(0).getBoolean("favorite"))
    }

    @Test fun filteringCombinesCategoryFavoritesAndSearchWithoutMutatingTheList() {
        assertEquals(5, ProfileFilter().apply(grouped).size)
        assertEquals(listOf("b"), ProfileFilter("").apply(grouped).map { it.id })
        assertEquals(listOf("a", "c", "d"), ProfileFilter("work").apply(grouped).map { it.id })
        assertEquals(listOf("a", "c", "e"), ProfileFilter(favoritesOnly = true).apply(grouped).map { it.id })
        assertEquals(listOf("a", "c"), ProfileFilter("work", true).apply(grouped).map { it.id })
        assertEquals(listOf("c"), ProfileFilter("work", true).apply(grouped, "  EXAMPLE.COM/C  ").map { it.id })
        assertTrue(ProfileFilter("", true).apply(grouped).isEmpty())
        assertEquals(listOf("a", "b", "c", "d", "e"), grouped.profiles.map { it.id })
    }

    @Test fun filteredDragOnlyReordersVisibleSlotsAndRetainsHiddenProfiles() {
        val filter = ProfileFilter("work", true)
        val next = grouped.reorderVisible(listOf("c", "a"), filter)
        assertEquals(listOf("c", "b", "a", "d", "e"), next.profiles.map { it.id })
        assertEquals(grouped.profiles[1], next.profiles[1])
        assertEquals(grouped.profiles[3], next.profiles[3])
        assertEquals(grouped.profiles[4], next.profiles[4])
        assertEquals(grouped.lastProfileId, next.lastProfileId)
        assertEquals(grouped.categories, next.categories)
        assertEquals(next, ConfigCodec.decode(ConfigCodec.encode(next)))
        listOf(listOf("a"), listOf("c", "c"), listOf("a", "b"), listOf("a", "missing")).forEach {
            assertThrows(IllegalArgumentException::class.java) { grouped.reorderVisible(it, filter) }
        }
    }

    @Test fun topIsASingleReorderAndFavoriteDoesNotChangeStartupOrOrder() {
        val favorite = grouped.toggleFavorite("b")
        assertTrue(favorite.profiles[1].favorite)
        assertEquals(grouped.profiles.map { it.id }, favorite.profiles.map { it.id })
        assertEquals(grouped, favorite.toggleFavorite("b"))
        val top = favorite.moveToTop("c")
        assertEquals(listOf("c", "a", "b", "d", "e"), top.profiles.map { it.id })
        assertEquals("b", top.lastProfileId)
        assertEquals(favorite.categories, top.categories)
        assertEquals(top, top.moveToTop("c"))
        assertEquals("a", top.reorder(listOf("a", "b", "c", "d", "e")).profiles.first().id)
    }

    @Test fun selectingEditingRemovingAndClearingAllRetainTheCategoryRegistry() {
        val edited = grouped.select("e").upsert(grouped.profiles[0].copy(name = "新名称"))
        assertEquals(grouped.categories, edited.categories)
        assertTrue(edited.profiles.first().favorite)
        var cleared = edited
        grouped.profiles.forEach { cleared = cleared.remove(it.id) }
        assertTrue(cleared.profiles.isEmpty())
        assertNull(cleared.lastProfileId)
        assertEquals(grouped.categories, cleared.categories)
        assertEquals(cleared, ConfigCodec.decode(ConfigCodec.encode(cleared)))
    }

    @Test fun malformedCategoryMapsReferencesAndFavoritesAreRejected() {
        listOf("null", "[]", "42", "\"text\"", """{"":"空ID"}""", """{"x":" "}""",
            """{"x":4}""", """{"x":true}""").forEach { value ->
            val root = JSONObject(ConfigCodec.encode(grouped))
            root.put("categories", org.json.JSONTokener(value).nextValue())
            assertThrows(Exception::class.java) { ConfigCodec.decode(root.toString()) }
        }
        listOf("missing", "", 42, true).forEach { value ->
            val root = JSONObject(ConfigCodec.encode(grouped))
            root.getJSONArray("profiles").getJSONObject(0).put("categoryId", value)
            assertThrows(Exception::class.java) { ConfigCodec.decode(root.toString()) }
        }
        listOf("true", 1, JSONObject.NULL).forEach { value ->
            val root = JSONObject(ConfigCodec.encode(grouped))
            root.getJSONArray("profiles").getJSONObject(0).put("favorite", value)
            assertThrows(Exception::class.java) { ConfigCodec.decode(root.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.EMPTY.upsert(profile("x", categoryId = "missing"))
        }
        assertThrows(IllegalArgumentException::class.java) { AppConfig.normalizeCategoryName("x".repeat(61)) }
        assertThrows(IllegalArgumentException::class.java) { AppConfig.normalizeCategoryName("a\nb") }
    }

    @Test fun disappearingCategoryResetsOnlyTheCategoryFilter() {
        assertEquals(ProfileFilter(favoritesOnly = true), ProfileFilter("missing", true).resolve(grouped))
        assertEquals(ProfileFilter("", true), ProfileFilter("", true).resolve(grouped))
        assertEquals(ProfileFilter("work", true), ProfileFilter("work", true).resolve(grouped))
        assertEquals(grouped, grouped.reorderVisible(emptyList(), ProfileFilter("", true)))
    }

    @Test fun importingDuplicateDisplayNamesIsRejectedBeforeReplacingCurrentConfiguration() {
        val root = JSONObject(ConfigCodec.encode(grouped))
        root.put("categories", JSONObject().put("id-a", " Work ").put("id-b", "work"))
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(root.toString()) }
        assertEquals(mapOf("work" to "工作", "play" to "娱乐"), grouped.categories)
    }
}

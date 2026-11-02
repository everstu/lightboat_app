package com.lightboat.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProfileOrganizationTest {
    private fun profile(id: String, favorite: Boolean = false, categoryId: String? = null) =
        Profile(id, "配置$id", "https://example.com/$id", categoryId = categoryId, favorite = favorite)

    private val grouped = AppConfig("b", listOf(
        profile("a", true, "work"), profile("b"), profile("c", true, "work"),
        profile("d", false, "work"), profile("e", true, "play")
    ), linkedMapOf("work" to "工作", "play" to "娱乐"))

    @Test fun legacyConfigurationDefaultsToUncategorizedAndNotFavorite() {
        val legacy = """{"version":1,"lastProfileId":"a","profiles":[{"id":"a","name":"旧配置","url":"https://example.com"}]}"""
        val config = ConfigCodec.decode(legacy)
        assertTrue(config.categories.isEmpty())
        assertNull(config.defaultProfile?.categoryId)
        assertFalse(config.defaultProfile!!.favorite)
    }

    @Test fun inlineCategoryCreationUsesAnIndependentIdAndReusesExistingNames() {
        val first = AppConfig.EMPTY.upsert(profile("a"), "  Work  ")
        val id = first.defaultProfile!!.categoryId!!
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
        assertEquals(grouped.defaultProfileId, cleared.defaultProfileId)
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
        assertEquals(grouped.defaultProfileId, next.defaultProfileId)
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
        assertEquals("b", top.defaultProfileId)
        assertEquals(favorite.categories, top.categories)
        assertEquals(top, top.moveToTop("c"))
        assertEquals("a", top.reorder(listOf("a", "b", "c", "d", "e")).profiles.first().id)
    }

    @Test fun selectingEditingRemovingAndClearingAllRetainTheCategoryRegistry() {
        val edited = grouped.setDefault("e").upsert(grouped.profiles[0].copy(name = "新名称"))
        assertEquals(grouped.categories, edited.categories)
        assertTrue(edited.profiles.first().favorite)
        var cleared = edited
        grouped.profiles.forEach { cleared = cleared.remove(it.id) }
        assertTrue(cleared.profiles.isEmpty())
        assertNull(cleared.defaultProfileId)
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

    @Test fun renamingCategoryKeepsItsIdAllProfilesSelectionFavoritesAndOrder() {
        val next = grouped.renameCategory("work", "  日常  ")
        assertEquals("日常", next.categories["work"])
        assertEquals(grouped.categories.keys.toList(), next.categories.keys.toList())
        assertEquals("娱乐", next.categories["play"])
        assertEquals(grouped.profiles, next.profiles)
        assertEquals(grouped.defaultProfileId, next.defaultProfileId)
        assertEquals(ProfileFilter("work"), ProfileFilter("work").resolve(next))
        assertEquals(listOf("a", "c", "d"), ProfileFilter("work").apply(next).map { it.id })
        assertEquals(next, ConfigCodec.decode(ConfigCodec.encode(next)))
    }

    @Test fun renameRejectsUnknownEmptyDuplicateAndInvalidNamesWithoutChangingTheRegistry() {
        assertThrows(IllegalArgumentException::class.java) { grouped.renameCategory("missing", "新分类") }
        listOf("", "  ", "娱乐", "a\nb", "x".repeat(61)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { grouped.renameCategory("work", name) }
        }
        val latin = grouped.copy(categories = grouped.categories + ("unused" to "Work"))
        assertThrows(IllegalArgumentException::class.java) { latin.renameCategory("play", " work ") }
        assertEquals("WORK", latin.renameCategory("unused", "WORK").categories["unused"])
        assertEquals(grouped.profiles, latin.renameCategory("unused", "已归档").profiles)
        assertEquals(mapOf("work" to "工作", "play" to "娱乐"), grouped.categories)
    }

    @Test fun notesSurviveFavoritesCategoriesReorderingTopAndScriptLibraryChanges() {
        val notes = grouped.profiles.associate { it.id to "备注 ${it.id}\n\t缩进 😀" }
        val noted = grouped.copy(profiles = grouped.profiles.map { it.copy(note = notes.getValue(it.id)) })

        val favorited = noted.toggleFavorite("b")
        assertEquals(notes.getValue("b"), favorited.profiles.first { it.id == "b" }.note)

        val categorized = favorited.upsert(favorited.profiles.first(), "新分类")
        assertEquals(notes.getValue("a"), categorized.profiles.first().note)

        val reordered = categorized.reorder(listOf("e", "d", "c", "b", "a"))
        assertEquals(listOf("e", "d", "c", "b", "a").map { notes.getValue(it) }, reordered.profiles.map { it.note })

        val top = reordered.moveToTop("c")
        assertEquals(notes.getValue("c"), top.profiles.first().note)
        assertEquals(reordered.defaultProfileId, top.defaultProfileId)

        val withScript = top.upsertScript(SharedScript("shared", "脚本", "https://scripts.example/a.js"))
        assertTrue(withScript.profiles.all { it.note == notes.getValue(it.id) })
        val cleared = withScript.replaceScriptLibrary(emptyList())
        assertTrue(cleared.profiles.all { it.note == notes.getValue(it.id) })

        val roundTripped = ConfigCodec.decode(ConfigCodec.encode(cleared))
        assertEquals(cleared.profiles.associate { it.id to it.note }, roundTripped.profiles.associate { it.id to it.note })
        val removed = roundTripped.remove("b")
        assertTrue(removed.profiles.all { it.note == notes.getValue(it.id) })
    }

    @Test fun deletingCategoryRetainsProfilesAndMovesThemToUncategorized() {
        val next = grouped.removeCategory("work")
        assertEquals(mapOf("play" to "娱乐"), next.categories)
        assertEquals(listOf("a", "b", "c", "d", "e"), next.profiles.map { it.id })
        assertEquals("b", next.defaultProfileId)
        assertNull(next.profiles.first { it.id == "a" }.categoryId)
        assertNull(next.profiles.first { it.id == "c" }.categoryId)
        assertNull(next.profiles.first { it.id == "d" }.categoryId)
        assertNull(next.profiles.first { it.id == "b" }.categoryId)
        assertEquals("play", next.profiles.first { it.id == "e" }.categoryId)
        // Favorites, order and the untouched category survive; the original config is unchanged.
        assertTrue(next.profiles.first { it.id == "a" }.favorite)
        assertTrue(next.profiles.first { it.id == "c" }.favorite)
        assertEquals(setOf("a", "c", "e"), next.profiles.filter { it.favorite }.map { it.id }.toSet())
        assertEquals(mapOf("work" to "工作", "play" to "娱乐"), grouped.categories)
        assertThrows(IllegalArgumentException::class.java) { grouped.removeCategory("missing") }
    }

    @Test fun deletedCategoryDisappearsFromJsonWithoutDanglingReferences() {
        val next = grouped.removeCategory("work")
        val root = JSONObject(ConfigCodec.encode(next))
        assertFalse(root.getJSONObject("categories").has("work"))
        assertEquals(1, root.getJSONObject("categories").length())
        assertTrue(root.getJSONArray("profiles").getJSONObject(0).isNull("categoryId"))
        assertEquals(next, ConfigCodec.decode(ConfigCodec.encode(next)))
        assertEquals(5, next.profiles.size)
        assertEquals(grouped.defaultProfileId, next.defaultProfileId)
        // Filtering resolves a deleted category to "all categories" while keeping favorites.
        assertEquals(ProfileFilter(favoritesOnly = true), ProfileFilter("work", true).resolve(next))
        assertEquals(ProfileFilter("play", true), ProfileFilter("play", true).resolve(next))
        assertEquals(ProfileFilter("", true), ProfileFilter("", true).resolve(next))
        assertEquals(listOf("e"), ProfileFilter("play").apply(next).map { it.id })
    }

    @Test fun deletingTheLastCategoryKeepsConfigurationsFavoritesAndRecreationRules() {
        val one = AppConfig("a", listOf(profile("a", true, "work"), profile("b")), linkedMapOf("work" to "工作"))
        val next = one.removeCategory("work")
        assertTrue(next.categories.isEmpty())
        assertEquals(2, next.profiles.size)
        assertNull(next.profiles.first().categoryId)
        assertTrue(next.profiles.first().favorite)
        assertEquals("a", next.defaultProfileId)
        assertEquals(next, ConfigCodec.decode(ConfigCodec.encode(next)))
        // Re-entering the deleted name later creates a fresh ID instead of restoring the old one.
        val recreated = next.upsert(profile("c"), "工作")
        val newId = recreated.profiles.last().categoryId!!
        assertNotEquals("work", newId)
        assertEquals("工作", recreated.categories[newId])
    }

    @Test fun deletingCategoryPreservesNotesScriptsAccessAndUnrelatedProfiles() {
        val scripts = listOf(SharedScript("s", "脚本", "https://scripts.example/s.js"))
        val base = grouped.copy(scriptLibrary = scripts,
            profiles = grouped.profiles.map { it.copy(note = "备注 ${it.id}", scripts = listOf(ProfileScript("s", false))) })
        val next = base.removeCategory("play")
        assertEquals(scripts, next.scriptLibrary)
        assertEquals(base.profiles.map { it.id to it.note }, next.profiles.map { it.id to it.note })
        assertTrue(next.profiles.all { it.scripts == listOf(ProfileScript("s", false)) })
        assertEquals(base.profiles.map { it.userAgent }, next.profiles.map { it.userAgent })
        assertEquals(base.profiles.map { it.platform }, next.profiles.map { it.platform })
        assertEquals(base.profiles.map { it.favorite }, next.profiles.map { it.favorite })
        assertEquals(base.defaultProfileId, next.defaultProfileId)
        // Only the profiles of the deleted category move to null.
        assertNull(next.profiles.first { it.id == "e" }.categoryId)
        assertEquals("work", next.profiles.first { it.id == "a" }.categoryId)
        assertEquals(mapOf("work" to "工作"), next.categories)
        assertEquals(next, ConfigCodec.decode(ConfigCodec.encode(next)))
    }
}

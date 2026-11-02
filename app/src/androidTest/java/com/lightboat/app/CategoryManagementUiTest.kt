package com.lightboat.app

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CategoryManagementUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("profile_ui", 0)
    private val configFile get() = File(context.filesDir, "config.json")
    private var originalConfig: ByteArray? = null
    private var originalPreferences = emptyMap<String, Any?>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        originalConfig = configFile.takeIf { it.exists() }?.readBytes()
        originalPreferences = preferences.all.toMap()
        preferences.edit().clear().commit()
    }

    @After fun tearDown() {
        scenario?.close()
        scenario = null
        originalConfig?.let(configFile::writeBytes) ?: configFile.delete()
        preferences.edit().clear().apply {
            originalPreferences.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }.commit()
    }

    @Test fun deletingTheFilteredCategoryMovesProfilesAndClearsOnlyTheCategoryPart() {
        preferences.edit().putString("categoryId", "work").putBoolean("favoritesOnly", true).commit()
        val profiles = listOf(
            Profile("favorite", "收藏配置", "https://page.example/a", categoryId = "work", favorite = true),
            Profile("normal", "普通配置", "https://page.example/b", categoryId = "work"),
            Profile("other", "未分类配置", "https://page.example/c"))
        launch(AppConfig("favorite", profiles, linkedMapOf("work" to "工作", "play" to "娱乐")))
        scenario!!.onActivity { activity ->
            assertEquals(1, activity.findViewById<RecyclerView>(R.id.profilesList).adapter!!.itemCount)
            assertEquals(context.getString(R.string.category_rename_action),
                activity.findViewById<Button>(R.id.renameCategories).text.toString())
        }
        openManager()
        scenario!!.onActivity { activity ->
            val manager = categoryDialog(activity)
            assertEquals(context.getString(R.string.category_rename_title),
                manager.findViewById<TextView>(R.id.renameTitle)!!.text.toString())
            assertEquals(context.getString(R.string.category_save_name),
                manager.findViewById<Button>(R.id.saveRename)!!.text.toString())
            assertEquals(context.getString(R.string.category_rename_hint, 2),
                manager.findViewById<TextView>(R.id.renameHint)!!.text.toString())
            // Both action rows must keep their full height on screen.
            val save = manager.findViewById<Button>(R.id.saveRename)!!
            val bounds = android.graphics.Rect().also { save.getGlobalVisibleRect(it) }
            val frame = android.graphics.Rect().also { manager.window!!.decorView.getWindowVisibleDisplayFrame(it) }
            assertTrue("The action row is clipped: $bounds in $frame", bounds.height() >= save.height - 1)
            assertTrue("The action row is outside the visible window: $bounds in $frame", frame.contains(bounds))
        }
        // An unsaved rename neither reaches the registry nor the delete confirmation. Keep the
        // name short so it still fits the one-line category width limit on tiny, large-font screens.
        revealInBody(R.id.categoryNameInput, "categoryNameInput")
        setText("categoryNameInput", "临时")
        val unsavedName = node("categoryNameInput")!!.text.toString()
        assertTrue("The short category name was dropped: '$unsavedName'", unsavedName.isNotEmpty())
        capture("category-manager")
        revealInBody(R.id.deleteCategory, "deleteCategory")
        capture("category-manager-delete-visible")
        click("deleteCategory")
        var message = confirmationMessage()
        assertTrue(message, message.contains("工作"))
        assertTrue(message, message.contains("2 个配置"))
        assertFalse(message, message.contains(unsavedName))
        assertPositiveIsDestructive()
        capture("category-delete-confirm")
        clickConfirmation(DialogInterface.BUTTON_NEGATIVE)
        waitFor("Cancelling the confirmation did not return to the manager") { node("deleteCategory") != null }
        revealInBody(R.id.categoryNameInput, "categoryNameInput")
        assertEquals(unsavedName, node("categoryNameInput")!!.text.toString())
        assertEquals(linkedMapOf("work" to "工作", "play" to "娱乐"), load().categories)
        // Renaming still works and keeps the category ID.
        click("saveRename")
        waitFor("The renamed category was not saved") { load().categories["work"] == unsavedName }
        assertEquals(2, load().profiles.count { it.categoryId == "work" })
        // Delete now confirms with the saved name.
        openManager()
        revealInBody(R.id.deleteCategory, "deleteCategory")
        click("deleteCategory")
        message = confirmationMessage()
        assertTrue(message, message.contains(unsavedName))
        clickConfirmation(DialogInterface.BUTTON_POSITIVE)
        waitFor("The deleted category was not saved") { !load().categories.containsKey("work") }
        val saved = load()
        assertEquals(mapOf("play" to "娱乐"), saved.categories)
        assertEquals(3, saved.profiles.size)
        assertNull(saved.profiles.first { it.id == "favorite" }.categoryId)
        assertNull(saved.profiles.first { it.id == "normal" }.categoryId)
        assertNull(saved.profiles.first { it.id == "other" }.categoryId)
        assertTrue(saved.profiles.first { it.id == "favorite" }.favorite)
        assertEquals("favorite", saved.defaultProfileId)
        // Favorites filtering survives; the deleted category selection becomes "all".
        assertNull(preferences.getString("categoryId", null))
        assertTrue(preferences.getBoolean("favoritesOnly", false))
        scenario!!.onActivity { activity ->
            assertNull(MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity))
            assertEquals(context.getString(R.string.category_all),
                activity.findViewById<Button>(R.id.categoryFilter).text.toString())
            assertEquals(1, activity.findViewById<RecyclerView>(R.id.profilesList).adapter!!.itemCount)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.renameCategories).visibility)
        }
    }

    @Test fun emptyCategoriesRemainManageableAndTheEntryHidesAfterTheLastOne() {
        launch(AppConfig(null, emptyList(), linkedMapOf("empty" to "空分类")))
        openManager()
        revealInBody(R.id.deleteCategory, "deleteCategory")
        click("deleteCategory")
        val message = confirmationMessage()
        assertTrue(message, message.contains("暂无配置"))
        clickConfirmation(DialogInterface.BUTTON_POSITIVE)
        waitFor("The empty category was not deleted") { load().categories.isEmpty() }
        scenario!!.onActivity { activity ->
            assertNull(MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.renameCategories).visibility)
        }
        assertTrue(load().profiles.isEmpty())
    }

    @Test fun deletingTheLastCategoryKeepsEveryProfileAndTheStartupSelection() {
        val profiles = listOf(
            Profile("one", "配置一", "https://page.example/1", categoryId = "only"),
            Profile("two", "配置二", "https://page.example/2", categoryId = "only"))
        launch(AppConfig("one", profiles, linkedMapOf("only" to "唯一分类")))
        openManager()
        revealInBody(R.id.deleteCategory, "deleteCategory")
        click("deleteCategory")
        assertTrue(confirmationMessage().contains("2 个配置"))
        clickConfirmation(DialogInterface.BUTTON_POSITIVE)
        waitFor("The last category was not deleted") { load().categories.isEmpty() }
        val saved = load()
        assertEquals(2, saved.profiles.size)
        assertEquals(listOf("配置一", "配置二"), saved.profiles.map { it.name })
        assertTrue(saved.profiles.all { it.categoryId == null })
        assertEquals("one", saved.defaultProfileId)
        scenario!!.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.renameCategories).visibility)
            assertEquals(2, activity.findViewById<RecyclerView>(R.id.profilesList).adapter!!.itemCount)
            assertEquals(context.getString(R.string.category_all),
                activity.findViewById<Button>(R.id.categoryFilter).text.toString())
        }
    }

    @Test fun keyboardKeepsTheDeleteSectionAndActionsReachable() {
        preferences.edit().putString("categoryId", "work").commit()
        launch(AppConfig("a", listOf(
            Profile("a", "配置一", "https://page.example/1", categoryId = "work"),
            Profile("b", "配置二", "https://page.example/2", categoryId = "work")),
            linkedMapOf("work" to "工作")))
        openManager()
        revealInBody(R.id.categoryNameInput, "categoryNameInput")
        scenario!!.onActivity { activity ->
            val manager = categoryDialog(activity)
            val input = manager.findViewById<EditText>(R.id.categoryNameInput)!!
            input.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        var saveHeight = 0
        var bounds = android.graphics.Rect()
        var frame = android.graphics.Rect()
        fun measure() = scenario!!.onActivity { activity ->
            val manager = categoryDialog(activity)
            val save = manager.findViewById<Button>(R.id.saveRename)!!
            saveHeight = save.height
            bounds = android.graphics.Rect().also { save.getGlobalVisibleRect(it) }
            frame = android.graphics.Rect().also { manager.window!!.decorView.getWindowVisibleDisplayFrame(it) }
        }
        measure()
        if (frame.height() >= DialogLayout.dp(context, 240)) {
            // The keyboard leaves enough room, so the action row stays fully visible.
            waitFor("The action row is clipped while the keyboard is shown") {
                measure()
                bounds.height() >= saveHeight - 1 && frame.contains(bounds)
            }
            capture("category-manager-keyboard")
        } else {
            // A landscape keyboard can cover almost the whole short dialog. The row must keep its
            // full height, and dismissing the keyboard must bring it fully back on screen.
            assertTrue("The action row was squeezed: $bounds", bounds.height() >= saveHeight - 1)
            assertTrue("The action row lost its 48dp height", saveHeight >= DialogLayout.dp(context, 48))
            scenario!!.onActivity { activity ->
                val input = categoryDialog(activity).findViewById<EditText>(R.id.categoryNameInput)!!
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
            }
            waitFor("The action row did not return after the keyboard was hidden") {
                measure()
                bounds.height() >= saveHeight - 1 && frame.contains(bounds)
            }
            capture("category-manager-keyboard-hidden")
        }
    }

    @Test fun failedSaveKeepsBothWindowsAndTheSavedCategory() {
        preferences.edit().putString("categoryId", "work").putBoolean("favoritesOnly", true).commit()
        val profiles = listOf(Profile("a", "配置一", "https://page.example/1", categoryId = "work"))
        val config = AppConfig("a", profiles, linkedMapOf("work" to "工作"))
        launch(config)
        openManager()
        val savedBytes = configFile.readBytes()
        // A directory where AtomicFile writes its ".new" file makes the save fail deterministically.
        val blocked = File(context.filesDir, "config.json.new")
        assertTrue(blocked.mkdir())
        try {
            revealInBody(R.id.deleteCategory, "deleteCategory")
            click("deleteCategory")
            assertTrue(confirmationMessage().contains("工作"))
            clickConfirmation(DialogInterface.BUTTON_POSITIVE)
            waitFor("A failed save must keep the category and both windows") {
                var kept = false
                scenario!!.onActivity { activity ->
                    kept = runCatching { categoryDialog(activity).isShowing }.getOrDefault(false) &&
                        runCatching { confirmation(activity).isShowing }.getOrDefault(false)
                }
                kept
            }
            scenario!!.onActivity { activity ->
                val current = MainActivity::class.java.getDeclaredField("config")
                    .apply { isAccessible = true }.get(activity) as AppConfig
                assertEquals(setOf("work"), current.categories.keys)
                assertEquals("work", current.profiles.single().categoryId)
            }
            assertArrayEquals("A failed delete changed the saved configuration", savedBytes, configFile.readBytes())
            assertEquals("work", preferences.getString("categoryId", null))
            assertTrue(preferences.getBoolean("favoritesOnly", false))
        } finally {
            blocked.delete()
        }
    }

    @Test fun recreationDoesNotConfirmAPendingDelete() {
        val config = AppConfig("a", listOf(Profile("a", "配置一", "https://page.example/1", categoryId = "work")),
            linkedMapOf("work" to "工作", "play" to "娱乐"))
        launch(config)
        openManager()
        revealInBody(R.id.deleteCategory, "deleteCategory")
        click("deleteCategory")
        assertTrue(confirmationMessage().contains("工作"))
        scenario!!.recreate()
        waitFor("The pending delete survived recreation") { node("deleteCategory") == null }
        val saved = load()
        assertEquals(setOf("work", "play"), saved.categories.keys)
        assertEquals("work", saved.profiles.single().categoryId)
    }

    private fun openManager() {
        scenario!!.onActivity { it.findViewById<View>(R.id.renameCategories).performClick() }
        waitFor("The category manager did not open") { node("renameTitle") != null }
    }

    /**
     * Short landscape screens keep part of the body below the fold. Scroll the requested control
     * into view before a visibility-based interaction, as a user would.
     */
    private fun revealInBody(viewId: Int, nodeId: String) {
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { activity ->
            val manager = categoryDialog(activity)
            val scroll = manager.findViewById<BoundedScrollView>(R.id.renameScroll) ?: return@onActivity
            val body = scroll.getChildAt(0) ?: return@onActivity
            val target = manager.findViewById<View>(viewId) ?: return@onActivity
            val limit = (body.height - scroll.height).coerceAtLeast(0)
            val top = (target.top - (scroll.height - target.height) / 2).coerceIn(0, limit)
            scroll.scrollTo(0, top)
        }
        instrumentation.waitForIdleSync()
        waitFor("$nodeId did not scroll into view") { node(nodeId) != null }
    }

    private fun launch(config: AppConfig) {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        scenario = launchConfiguredActivity()
        // Startup can auto-open the last profile after launch; keep asking for the manager.
        waitFor("The configuration manager did not open") {
            var shown = false
            scenario!!.onActivity { activity ->
                shown = activity.findViewById<View>(R.id.profilesPanel).isShown
                if (!shown) {
                    MainActivity::class.java.getDeclaredMethod("showProfiles").apply { isAccessible = true }.invoke(activity)
                }
            }
            shown
        }
        waitFor("The configuration manager did not show the category entry") { node("renameCategories") != null }
    }

    private fun categoryDialog(activity: MainActivity): CategoryRenameDialog =
        MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }
            .get(activity) as CategoryRenameDialog

    private fun confirmation(activity: MainActivity): AlertDialog =
        CategoryRenameDialog::class.java.getDeclaredField("confirmation").apply { isAccessible = true }
            .get(categoryDialog(activity)) as AlertDialog

    private fun confirmationMessage(): String {
        var message = ""
        waitFor("The delete confirmation did not appear") {
            var ready = false
            scenario!!.onActivity { activity ->
                val dialog = runCatching { confirmation(activity) }.getOrNull()
                if (dialog != null && dialog.isShowing) {
                    message = dialog.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
                    ready = true
                }
            }
            ready
        }
        return message
    }

    /** The alert's buttons use AppCompat ids, so click the resolved button view directly. */
    private fun clickConfirmation(which: Int) {
        waitFor("The delete confirmation did not appear") {
            var ready = false
            scenario!!.onActivity { activity ->
                ready = runCatching { confirmation(activity).isShowing }.getOrDefault(false)
            }
            ready
        }
        scenario!!.onActivity { activity -> confirmation(activity).getButton(which).performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun assertPositiveIsDestructive() {
        scenario!!.onActivity { activity ->
            val positive = confirmation(activity).getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            assertEquals(context.getColor(R.color.error), positive.currentTextColor)
        }
    }

    private fun load() = ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load()

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun node(id: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByViewId("${context.packageName}:id/$id")?.firstOrNull { it.isVisibleToUser }

    private fun click(id: String) {
        waitFor("Could not click $id") { node(id)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        instrumentation.waitForIdleSync()
    }

    private fun setText(id: String, text: String) {
        waitFor("Missing $id") { node(id) != null }
        assertTrue(node(id)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        instrumentation.waitForIdleSync()
    }

    private fun waitFor(message: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
}

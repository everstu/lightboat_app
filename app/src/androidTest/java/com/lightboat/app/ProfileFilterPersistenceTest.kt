package com.lightboat.app

import android.os.SystemClock
import android.os.Bundle
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileFilterPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("profile_ui", 0)
    private val configFile get() = File(context.filesDir, "config.json")
    private var originalConfig: ByteArray? = null
    private var originalPreferences = emptyMap<String, Any?>()
    private var scenario: ActivityScenario<MainActivity>? = null
    @Volatile private var importConfig: AppConfig? = null
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = if (session.uri == "/config.json")
            newFixedLengthResponse(Response.Status.OK, "application/json", ConfigCodec.encode(requireNotNull(importConfig)))
        else newFixedLengthResponse(Response.Status.OK, "text/html", "<!doctype html><title>Filter persistence fixture</title><p>Ready</p>")
    }

    @Before fun setUp() {
        originalConfig = configFile.takeIf { it.exists() }?.readBytes()
        originalPreferences = preferences.all.toMap()
        preferences.edit().clear().commit()
        server.start()
        val url = "http://127.0.0.1:${server.listeningPort}/"
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig("favorite", listOf(
            Profile("favorite", "收藏配置", url, categoryId = "work", favorite = true),
            Profile("normal", "普通配置", url, categoryId = "work"),
            Profile("other", "未分类配置", url)
        ), mapOf("work" to "工作")))
        launch()
    }

    @After fun tearDown() {
        scenario?.close()
        server.stop()
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

    @Test fun managerRemembersBothEnabledAndDisabledAcrossFreshLaunches() {
        openFromBrowser("menuSettings")
        scenario!!.onActivity { activity -> activity.findViewById<Button>(R.id.favoriteFilter).performClick() }
        assertTrue("Selecting favorites was not persisted", preferences.getBoolean("favoritesOnly", false))
        assertManager(true, 1)
        relaunch()
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        scenario!!.onActivity { activity -> activity.findViewById<Button>(R.id.favoriteFilter).performClick() }
        assertFalse(preferences.getBoolean("favoritesOnly", true))
        relaunch()
        openFromBrowser("menuSettings")
        assertManager(false, 3)
    }

    @Test fun pickerSharesAndRemembersFavoriteSelectionWithTheManager() {
        openFromBrowser("menuSwitch")
        click("favoriteFilter")
        assertTrue("Picker did not persist favorite selection", preferences.getBoolean("favoritesOnly", false))
        assertTrue(node("favoriteFilter")!!.isSelected)
        click("closeSwitch")
        openFromBrowser("menuSwitch")
        assertTrue("Reopening the picker lost favorite selection", node("favoriteFilter")!!.isSelected)
        click("closeSwitch")
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        scenario!!.onActivity { activity -> activity.findViewById<View>(R.id.returnButton).performClick() }
        relaunch()
        openFromBrowser("menuSwitch")
        assertTrue("Fresh activity did not restore picker favorites", node("favoriteFilter")!!.isSelected)
        click("favoriteFilter")
        assertFalse(preferences.getBoolean("favoritesOnly", true))
        click("closeSwitch")
        openFromBrowser("menuSettings")
        assertManager(false, 3)
    }

    @Test fun recreationPreservesTheCurrentSelectionAndResetPersistsOff() {
        preferences.edit().putString("categoryId", "work").putBoolean("favoritesOnly", true).commit()
        relaunch()
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        scenario!!.recreate()
        assertManager(true, 1)
        scenario!!.onActivity { activity ->
            activity.findViewById<Button>(R.id.favoriteFilter).performClick()
            assertFalse(activity.findViewById<Button>(R.id.favoriteFilter).isSelected)
        }
        // A restored explicit false must win over a stale disk preference.
        preferences.edit().putBoolean("favoritesOnly", true).commit()
        scenario!!.recreate()
        assertManager(false, 2)
        scenario!!.onActivity { activity ->
            activity.findViewById<Button>(R.id.favoriteFilter).performClick()
            activity.findViewById<View>(R.id.resetProfileFilters).performClick()
        }
        assertNull(preferences.getString("categoryId", null))
        assertFalse(preferences.getBoolean("favoritesOnly", true))
        relaunch()
        openFromBrowser("menuSettings")
        assertManager(false, 3)
    }

    @Test fun missingCategoryResetsOnlyTheCategoryAndKeepsFavorites() {
        preferences.edit().putString("categoryId", "removed").putBoolean("favoritesOnly", true).commit()
        relaunch()
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        assertNull(preferences.getString("categoryId", null))
        assertTrue(preferences.getBoolean("favoritesOnly", false))
    }

    @Test fun pickerCategoryAndResetAreSharedAndPersistAcrossLaunches() {
        openFromBrowser("menuSwitch")
        click("categoryFilter")
        clickText("工作")
        click("favoriteFilter")
        assertEquals("work", preferences.getString("categoryId", null))
        click("closeSwitch")
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        scenario!!.onActivity { assertEquals("工作", it.findViewById<Button>(R.id.categoryFilter).text.toString()) }
        relaunch()
        openFromBrowser("menuSwitch")
        assertTrue(node("favoriteFilter")!!.isSelected)
        assertEquals("工作", node("categoryFilter")!!.text.toString())
        // Reset is offered for an empty result; choose a category with no favorites.
        click("categoryFilter")
        clickText("未分类")
        click("resetFilters")
        assertFalse(preferences.getBoolean("favoritesOnly", true))
        assertNull(preferences.getString("categoryId", null))
        click("closeSwitch")
        relaunch()
        openFromBrowser("menuSettings")
        assertManager(false, 3)
    }

    @Test fun importKeepsFavoritesAndOnlyResetsACategoryThatNoLongerExists() {
        val original = ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load()
        preferences.edit().putString("categoryId", "work").putBoolean("favoritesOnly", true).commit()
        relaunch()
        openFromBrowser("menuSettings")
        import(original.copy(categories = mapOf("work" to "工作（已导入）")))
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        assertEquals("work", preferences.getString("categoryId", null))
        import(original.copy(categories = emptyMap(), profiles = original.profiles.map { it.copy(categoryId = null) }))
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        assertNull(preferences.getString("categoryId", null))
        assertTrue(preferences.getBoolean("favoritesOnly", false))
        relaunch()
        openFromBrowser("menuSettings")
        assertManager(true, 1)
        assertEquals(1, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load().profiles.count { it.favorite })
    }

    private fun import(config: AppConfig) {
        importConfig = config
        scenario!!.onActivity { it.findViewById<View>(R.id.importButton).performClick() }
        waitFor("Import address field is missing") { node("importUrl") != null }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            "http://127.0.0.1:${server.listeningPort}/config.json") }
        assertTrue(node("importUrl")!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        click("importAction")
        waitFor("Import preview did not appear") { node("importAction")?.text?.toString() == context.getString(R.string.import_confirm) }
        click("importAction")
        waitFor("Imported configuration did not open") {
            var ready = false
            scenario!!.onActivity { ready = it.findViewById<View>(R.id.menuLayer).isShown }
            ready && node("importAction") == null
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Browser did not become visible") {
            var ready = false
            scenario!!.onActivity { ready = it.findViewById<View>(R.id.menuLayer).isShown }
            ready
        }
    }

    private fun relaunch() { scenario!!.close(); launch() }

    private fun openFromBrowser(action: String) {
        scenario!!.onActivity { activity -> activity.findViewById<View>(R.id.edgeMenuButton).performClick() }
        click(action)
    }

    private fun assertManager(selected: Boolean, count: Int) {
        scenario!!.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.profilesPanel).isShown)
            assertEquals(selected, activity.findViewById<Button>(R.id.favoriteFilter).isSelected)
            assertEquals(count, activity.findViewById<RecyclerView>(R.id.profilesList).adapter!!.itemCount)
        }
    }

    private fun node(id: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByViewId("${context.packageName}:id/$id")?.firstOrNull { it.isVisibleToUser }

    private fun click(id: String) {
        waitFor("Could not click $id") { node(id)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        instrumentation.waitForIdleSync()
    }

    private fun clickText(text: String) {
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        val target = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text)
            .first { it.text?.toString() == text && it.isVisibleToUser }
        val bounds = Rect().also(target::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 40 else 0,
                action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
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

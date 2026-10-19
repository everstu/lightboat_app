package com.lightboat.app

import android.os.SystemClock
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.Lifecycle
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
class DebugLogFilteringTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private var originalConfig: ByteArray? = null
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var dialog: DebugLogDialog

    @Before fun setUp() {
        originalConfig = configFile.takeIf { it.exists() }?.readBytes()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        DebugLog.clear()
        DebugLog.event("userscript", "info script alpha started")
        DebugLog.event("gm-xhr", "error alpha GM response too large")
        DebugLog.event("media", "load status=200 beta")
        scenario.onActivity { dialog = DebugLogDialog(it).apply { show() } }
        waitFor("Initial rows were not loaded") { count() == 3 }
    }

    @After fun tearDown() {
        if (::dialog.isInitialized) scenario.onActivity { dialog.dismiss() }
        if (::scenario.isInitialized) scenario.close()
        originalConfig?.let(configFile::writeBytes) ?: configFile.delete()
    }

    @Test fun filtersWhilePausedThenResumesAndRetainsRawHistory() {
        scenario.onActivity {
            dialog.findViewById<Button>(R.id.pauseLogs)!!.performClick()
            dialog.findViewById<EditText>(R.id.logQuery)!!.setText("ALPHA")
        }
        waitFor("Search did not match cached rows while paused") { count() == 2 }
        DebugLog.event("gm-xhr", "error alpha next request")
        SystemClock.sleep(700)
        scenario.onActivity { assertEquals("Paused rows were refreshed", 2, count()) }
        scenario.onActivity { dialog.findViewById<Button>(R.id.pauseLogs)!!.performClick() }
        waitFor("Matching live row was not appended") { count() == 3 }
        scenario.onActivity {
            assertEquals("复制筛选结果", dialog.findViewById<Button>(R.id.copyLogs)!!.text.toString())
            assertTrue(dialog.findViewById<TextView>(R.id.logCount)!!.text.contains("3 /"))
            dialog.findViewById<Button>(R.id.resetLogFilters)!!.performClick()
        }
        waitFor("Reset lost hidden rows") { count() == 4 }
        scenario.onActivity { dialog.findViewById<EditText>(R.id.logQuery)!!.setText("not-present-anywhere") }
        waitFor("Empty filtered state was not shown") {
            count() == 0 && dialog.findViewById<View>(R.id.emptyLogs)!!.visibility == View.VISIBLE &&
                dialog.findViewById<TextView>(R.id.emptyTitle)!!.text.toString() == "没有符合条件的日志"
        }
        scenario.onActivity { dialog.findViewById<Button>(R.id.clearLogs)!!.performClick() }
        waitFor("Clear did not complete") { dialog.findViewById<Button>(R.id.clearLogs)!!.isEnabled }
        scenario.onActivity { dialog.findViewById<Button>(R.id.resetLogFilters)!!.performClick() }
        waitFor("Cleared hidden history returned") { count() == 0 }
        DebugLog.event("userscript", "info after-clear")
        waitFor("Logging did not continue after clear") { count() == 1 }
        assertTrue(DebugLog.readAfter(null).text.contains("after-clear"))
        assertFalse(DebugLog.readAfter(null).text.contains("too large"))
    }

    @Test fun sourceAndLevelSelectorsCombineAndCopyOnlyVisibleRows() {
        scenario.onActivity { dialog.findViewById<Button>(R.id.logLevel)!!.performClick() }
        clickOption("错误")
        waitFor("Error filter did not select the failed request") { count() == 1 }
        scenario.onActivity { dialog.findViewById<Button>(R.id.logSource)!!.performClick() }
        clickOption("userscript")
        waitFor("Source and level filters were not combined") { count() == 0 }
        scenario.onActivity { dialog.findViewById<Button>(R.id.logLevel)!!.performClick() }
        clickOption("信息")
        waitFor("Changing a level lost the selected source") { count() == 1 }
        scenario.onActivity { activity ->
            dialog.findViewById<Button>(R.id.copyLogs)!!.performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.contains("script alpha started"))
            assertFalse(copied.contains("GM response"))
            assertFalse(copied.contains("beta"))
            dialog.findViewById<Button>(R.id.resetLogFilters)!!.performClick()
        }
        waitFor("Reset did not restore all sources and levels") { count() == 3 }
    }

    @Test fun pausedWindowAndFileRetainOnlyLatest2000AfterOverflow() {
        scenario.onActivity { dialog.findViewById<Button>(R.id.pauseLogs)!!.performClick() }
        repeat(2400) { DebugLog.event("retention", "record-$it") }
        DebugLog.flush()
        val disk = File(context.filesDir, "debug.log").readLines()
        assertEquals(2000, disk.size)
        assertTrue(disk.first().endsWith("record-400"))
        assertTrue(disk.last().endsWith("record-2399"))
        scenario.onActivity { dialog.findViewById<Button>(R.id.pauseLogs)!!.performClick() }
        waitFor("Expired cursor did not reset to 2000 retained records") { count() == 2000 }
        scenario.onActivity { dialog.findViewById<EditText>(R.id.logQuery)!!.setText("record-2399") }
        waitFor("Newest row did not survive overflow") { count() == 1 }
        scenario.onActivity { dialog.findViewById<EditText>(R.id.logQuery)!!.setText("script alpha") }
        waitFor("Old filtered records were not released") { count() == 0 }
    }

    @Test fun returningFromBackgroundResumesRefreshAndAllowsClearing() {
        scenario.moveToState(Lifecycle.State.CREATED)
        DebugLog.event("userscript", "info while-in-background")
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Log dialog did not resume after the activity returned") { count() >= 4 }
        scenario.onActivity { dialog.findViewById<Button>(R.id.clearLogs)!!.performClick() }
        waitFor("Clear did not complete after background return") { count() == 0 && dialog.findViewById<Button>(R.id.clearLogs)!!.isEnabled }
        DebugLog.event("userscript", "info after-background-clear")
        waitFor("Log dialog did not resume after clearing") { count() == 1 }
    }

    @Test fun concurrentFlushAndClearCannotRestoreOldDiskRecords() {
        repeat(2200) { DebugLog.event("retention", "before-clear-$it " + "x".repeat(1000)) }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val writes = worker.submit { repeat(8) { DebugLog.flush() } }
            DebugLog.clear()
            DebugLog.event("retention", "after-concurrent-clear")
            writes.get(10, java.util.concurrent.TimeUnit.SECONDS)
            DebugLog.flush()
            val retained = DebugLog.readAfter(null).lines
            assertEquals(retained, File(context.filesDir, "debug.log").readLines())
            assertEquals(1, retained.size)
            assertTrue(retained.single().endsWith("after-concurrent-clear"))
            waitFor("Live logs stopped after concurrent persistence") { count() == 1 }
        } finally { worker.shutdownNow() }
    }

    private fun clickOption(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Popup enter/layout animations can move rows after their first accessibility snapshot.
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        SystemClock.sleep(200)
        val until = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < until) {
            val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            if (node != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 40 else 0,
                        action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.uiAutomation.injectInputEvent(event, true)
                    event.recycle()
                }
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "log-filter-dropdown-failure.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        fun describe(node: AccessibilityNodeInfo?): String = if (node == null) "<no root>" else
            "[${node.className} text=${node.text} description=${node.contentDescription} clickable=${node.isClickable}]" +
                (0 until node.childCount).joinToString("") { describe(node.getChild(it)) }
        println(describe(instrumentation.uiAutomation.rootInActiveWindow))
        fail("Dropdown option was not clickable: $text")
    }

    private fun count() = dialog.findViewById<RecyclerView>(R.id.logList)!!.adapter!!.itemCount

    private fun waitFor(message: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            scenario.onActivity { done = check() }
            if (done) return
            SystemClock.sleep(50)
        }
        var state = ""
        scenario.onActivity { state = " rows=${count()} source=${dialog.findViewById<Button>(R.id.logSource)!!.text} level=${dialog.findViewById<Button>(R.id.logLevel)!!.text} query=${dialog.findViewById<EditText>(R.id.logQuery)!!.text}" }
        fail(message + state + " logs=" + DebugLog.readAfter(null).text.takeLast(1500))
    }
}

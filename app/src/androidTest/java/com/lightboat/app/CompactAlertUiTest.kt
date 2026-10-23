package com.lightboat.app

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Exercise AppCompat's message, three-action and list contracts with the shared alert layout. */
@RunWith(AndroidJUnit4::class)
class CompactAlertUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private var originalConfig: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var alert: AlertDialog? = null

    @Before fun setUp() {
        originalConfig = configFile.takeIf(File::exists)?.readBytes()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY)
        scenario = launchConfiguredActivity()
    }
    @After fun tearDown() {
        scenario?.onActivity { alert?.dismiss() }
        scenario?.close()
        originalConfig?.let(configFile::writeBytes) ?: configFile.delete()
    }

    @Test fun allThreeActionsRemainReadableReachableAndDispatchTheirOwnCallbacks() {
        val chosen = AtomicInteger(0)
        for (which in listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)) {
            chosen.set(0)
            scenario!!.onActivity { activity ->
                alert = AlertDialog.Builder(activity).setTitle(R.string.scan_result)
                    .setMessage("https://example.test/scan\n" + "较长的扫码结果仍应可以滚动阅读，操作按钮可达。\n".repeat(8))
                    .setPositiveButton(R.string.copy) { _, _ -> chosen.set(DialogInterface.BUTTON_POSITIVE) }
                    .setNegativeButton(R.string.close) { _, _ -> chosen.set(DialogInterface.BUTTON_NEGATIVE) }
                    .setNeutralButton(R.string.scan_again) { _, _ -> chosen.set(DialogInterface.BUTTON_NEUTRAL) }
                    .create().also { it.show() }
            }
            instrumentation.waitForIdleSync()
            tap {
                alert!!.getButton(which).also { button ->
                    assertTrue("Button label is clipped", button.layout.height <=
                        button.height - button.compoundPaddingTop - button.compoundPaddingBottom + 1)
                    for (line in 0 until button.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
                }
            }
            waitFor { chosen.get() == which && alert?.isShowing == false }
        }
    }

    @Test fun scriptCommandListsStillScrollAndSelectTheRequestedItem() {
        val selected = AtomicInteger(-1)
        val commands = Array(24) { "脚本命令 ${it + 1}" }
        scenario!!.onActivity { activity ->
            alert = AlertDialog.Builder(activity).setTitle(R.string.script_commands)
                .setItems(commands) { _, index -> selected.set(index) }
                .setNegativeButton(R.string.close, null).create().also { it.show() }
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { alert!!.listView.setSelection(commands.lastIndex) }
        waitFor {
            val list = alert!!.listView
            list.lastVisiblePosition == commands.lastIndex
        }
        tap {
            val list = alert!!.listView
            list.getChildAt(commands.lastIndex - list.firstVisiblePosition)
        }
        waitFor { selected.get() == commands.lastIndex && alert?.isShowing == false }
    }

    private fun tap(target: () -> View) {
        scenario!!.onActivity { target().let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) } }
        instrumentation.waitForIdleSync()
        waitFor {
            val view = target()
            val bounds = Rect()
            val origin = IntArray(2).also(view.rootView::getLocationOnScreen)
            val frame = Rect().also(view::getWindowVisibleDisplayFrame)
            val visible = view.getGlobalVisibleRect(bounds)
            bounds.offset(origin[0], origin[1])
            visible && bounds.height() >= view.height - 1 && frame.contains(bounds)
        }
        if (InstrumentationRegistry.getArguments().getString("captureUi") == "true") {
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            bitmap?.let {
                val name = if (alert!!.listView != null) "compact-alert-commands" else "compact-alert-actions"
                File(context.getExternalFilesDir(null), "$name.png").outputStream().use { stream -> it.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                it.recycle()
            }
        }
        var x = 0f; var y = 0f
        scenario!!.onActivity { target().let { view ->
            val location = IntArray(2).also(view::getLocationOnScreen)
            x = location[0] + view.width / 2f; y = location[1] + view.height / 2f
            assertTrue(view.height >= (48 * context.resources.displayMetrics.density).toInt())
        } }
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitFor(check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false
            scenario!!.onActivity { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("The compact alert did not reach its expected state")
    }
}

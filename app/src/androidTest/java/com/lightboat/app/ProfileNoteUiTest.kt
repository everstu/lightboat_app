package com.lightboat.app

import android.app.Dialog
import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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
class ProfileNoteUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private var backup: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        backup = configFile.takeIf(File::exists)?.readBytes()
    }

    @After fun tearDown() {
        scenario?.close()
        backup?.let(configFile::writeBytes) ?: configFile.delete()
    }

    @Test fun formSavesClearsAndEnforcesTheUtf16Limit() {
        val original = "原备注 😀\n第二行\t缩进   "
        launch(profileConfig(Profile("note", "备注配置", "https://page.example/", note = original)))
        openEditor(0)
        scenario!!.onActivity { activity ->
            val input = activity.findViewById<EditText>(R.id.noteInput)
            val counter = activity.findViewById<TextView>(R.id.noteCounter)
            assertEquals(original, input.text.toString())
            assertEquals(activity.getString(R.string.profile_note_length, original.length,
                AppConfig.MAX_PROFILE_NOTE_LENGTH), counter.text.toString())

            input.setText("x".repeat(AppConfig.MAX_PROFILE_NOTE_LENGTH + 50))
            assertEquals(AppConfig.MAX_PROFILE_NOTE_LENGTH, input.text.length)
            assertEquals(activity.getString(R.string.profile_note_limit_reached,
                AppConfig.MAX_PROFILE_NOTE_LENGTH), counter.text.toString())

            input.setText("保存备注\n<b>文本</b>\t😀")
            activity.findViewById<View>(R.id.saveButton).performClick()
        }
        assertEquals("保存备注\n<b>文本</b>\t😀", load().profiles.single().note)

        openEditor(0)
        scenario!!.onActivity { activity ->
            activity.findViewById<EditText>(R.id.noteInput).setText("")
            activity.findViewById<View>(R.id.saveButton).performClick()
        }
        assertEquals("", load().profiles.single().note)
    }

    @Test fun unsavedNoteSurvivesRecreationPlatformSwitchAndScriptPickerButNotCancel() {
        launch(profileConfig(Profile("note", "备注配置", "https://page.example/", note = "已保存")))
        openEditor(0)
        val draft = "未保存草稿\n\t第二行 😀"
        scenario!!.onActivity { activity ->
            activity.findViewById<EditText>(R.id.noteInput).setText(draft)
            activity.findViewById<View>(R.id.applePlatformButton).performClick()
            assertEquals(draft, activity.findViewById<EditText>(R.id.noteInput).text.toString())
        }

        scenario!!.recreate()
        waitForActivity("Editor with restored note") { activity ->
            activity.findViewById<View>(R.id.configPanel).isShown
        }
        scenario!!.onActivity { activity ->
            assertEquals(draft, activity.findViewById<EditText>(R.id.noteInput).text.toString())
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        waitUntil("Script library dialog") { dialogOrNull(scenario!!) != null }
        scenario!!.onActivity { dialogOrNull(it)?.dismiss() }
        waitUntil("Script library dialog dismissed") { dialogOrNull(scenario!!) == null }
        scenario!!.onActivity { activity ->
            assertEquals("Opening the script picker must not change the note",
                draft, activity.findViewById<EditText>(R.id.noteInput).text.toString())
            activity.findViewById<View>(R.id.cancelButton).performClick()
        }
        waitForActivity("Profile manager") { it.findViewById<View>(R.id.profilesPanel).isShown }

        scenario!!.onActivity { activity ->
            val add = activity.findViewById<View>(R.id.addButton)
            if (!add.isSelected) add.performClick()
            add.performClick()
        }
        waitForActivity("New profile editor") { it.findViewById<View>(R.id.configPanel).isShown }
        scenario!!.onActivity { activity ->
            assertEquals("", activity.findViewById<EditText>(R.id.noteInput).text.toString())
            assertEquals(activity.getString(R.string.profile_note_length, 0, AppConfig.MAX_PROFILE_NOTE_LENGTH),
                activity.findViewById<TextView>(R.id.noteCounter).text.toString())
            activity.findViewById<View>(R.id.cancelButton).performClick()
        }
        assertEquals("已保存", load().profiles.single().note)
    }

    @Test fun dialogSelectsCopiesAllWithoutDismissingAndHandlesAnEmptyNote() {
        val note = "长备注 😀\n\t缩进 <b>文本</b>"
        launch(AppConfig("with-note", listOf(
            Profile("with-note", "有备注", "https://page.example/", note = note),
            Profile("empty", "无备注", "https://page.example/"),
        )))
        openNoteDialog(Profile("with-note", "有备注", "https://page.example/", note = note))
        scenario!!.onActivity { activity ->
            val dialog = dialogOrNull(activity)!!
            val text = dialog.findViewById<TextView>(R.id.noteText)!!
            assertEquals(note, text.text.toString())
            assertTrue("Note body must allow selection", text.isTextSelectable)
            assertTrue("Note body must be long-clickable", text.isLongClickable)
            assertNotNull("Note body must have a selection movement method", text.movementMethod)
            assertEquals(activity.getString(R.string.profile_note_dialog_profile, "有备注"),
                dialog.findViewById<TextView>(R.id.noteProfile)!!.text.toString())
            val copy = dialog.findViewById<Button>(R.id.copyNote)!!
            val close = dialog.findViewById<Button>(R.id.closeNote)!!
            assertEquals(activity.getString(R.string.copy_all), copy.text.toString())
            assertEquals(activity.getString(R.string.close), close.text.toString())

            copy.performClick()
            assertEquals(note, clipboardText())
            assertTrue("Copy all must keep the dialog open", dialog.isShowing)
            close.performClick()
        }
        waitForActivity("Note dialog closed") { dialogOrNull(it) == null }

        openNoteDialog(Profile("empty", "无备注", "https://page.example/"))
        scenario!!.onActivity { activity ->
            val dialog = dialogOrNull(activity)!!
            val text = dialog.findViewById<TextView>(R.id.noteText)!!
            assertEquals(activity.getString(R.string.profile_note_empty), text.text.toString())
            assertFalse("The empty placeholder must not be selectable", text.isTextSelectable)
            assertEquals("Empty notes must hide the copy-all action", View.GONE,
                dialog.findViewById<Button>(R.id.copyNote)!!.visibility)
            val close = dialog.findViewById<Button>(R.id.closeNote)!!
            assertEquals(View.VISIBLE, close.visibility)
            assertEquals(activity.getString(R.string.close), close.text.toString())
            assertTrue(dialog.isShowing)
            close.performClick()
        }
        waitForActivity("Empty note dialog closed") { dialogOrNull(it) == null }
    }

    @Test fun longNoteScrollsAndKeepsActionsReachableInLandscape() {
        val note = (1..30).joinToString("\n") { "第 $it 行 " + "很长的横向字符串".repeat(4) } +
            "\n" + "x".repeat(300)
        launch(profileConfig(Profile("long", "长备注", "https://page.example/", note = note)))
        openNoteDialog(Profile("long", "长备注", "https://page.example/", note = note))
        scenario!!.onActivity { activity ->
            val dialog = dialogOrNull(activity)!!
            val scroll = dialog.findViewById<View>(R.id.noteScroll)!!
            assertTrue("Long note must scroll vertically", scroll.canScrollVertically(1))
            assertButtonIntact(activity, dialog.findViewById<Button>(R.id.copyNote)!!)
            assertButtonIntact(activity, dialog.findViewById<Button>(R.id.closeNote)!!)
        }
        val automation = instrumentation.uiAutomation
        try {
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
            waitForActivity("Landscape configuration") {
                it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            scenario!!.onActivity { activity ->
                assertExpectedConfiguration(activity)
                val dialog = dialogOrNull(activity)!!
                val scroll = dialog.findViewById<View>(R.id.noteScroll)!!
                assertTrue("Note dialog was dismissed by rotation", dialog.isShowing)
                assertTrue("Note body disappeared in landscape", scroll.height > 0)
                val minimumBody = (24 * activity.resources.displayMetrics.density).toInt()
                assertTrue("Note body was compressed to ${scroll.height}px (minimum $minimumBody)",
                    scroll.height >= minimumBody)
                assertButtonIntact(activity, dialog.findViewById<Button>(R.id.copyNote)!!)
                assertButtonIntact(activity, dialog.findViewById<Button>(R.id.closeNote)!!)
            }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
        scenario!!.onActivity { activity ->
            dialogOrNull(activity)!!.findViewById<Button>(R.id.closeNote)!!.performClick()
        }
        waitForActivity("Long note dialog closed") { dialogOrNull(it) == null }
    }

    @Test fun longPressSelectsNoteTextForNativeCopy() {
        val note = "长按选择这段备注文字"
        launch(profileConfig(Profile("select", "可选择", "https://page.example/", note = note)))
        openNoteDialog(Profile("select", "可选择", "https://page.example/", note = note))
        var x = 0f
        var y = 0f
        scenario!!.onActivity { activity ->
            val text = dialogOrNull(activity)!!.findViewById<TextView>(R.id.noteText)!!
            val location = IntArray(2).also(text::getLocationOnScreen)
            x = location[0] + text.width / 2f
            y = location[1] + text.height / 2f
        }
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        SystemClock.sleep(900)
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
        waitForActivity("Long press text selection") { activity ->
            val text = dialogOrNull(activity)?.findViewById<TextView>(R.id.noteText)
            text != null && text.selectionEnd > text.selectionStart
        }
        scenario!!.onActivity { activity ->
            dialogOrNull(activity)!!.findViewById<Button>(R.id.closeNote)!!.performClick()
        }
        waitForActivity("Selection dialog closed") { dialogOrNull(it) == null }
    }

    private fun profileConfig(profile: Profile) = AppConfig(profile.id, listOf(profile))

    private fun launch(config: AppConfig) {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("showProfiles").apply { isAccessible = true }.invoke(activity)
        }
        waitForActivity("Profile manager list") { activity ->
            activity.findViewById<View>(R.id.profilesPanel).isShown &&
                activity.findViewById<RecyclerView>(R.id.profilesList).childCount == config.profiles.size
        }
    }

    private fun openEditor(position: Int) {
        waitForActivity("Profile card $position") { activity ->
            activity.findViewById<RecyclerView>(R.id.profilesList).childCount > position
        }
        scenario!!.onActivity { activity ->
            activity.findViewById<RecyclerView>(R.id.profilesList).getChildAt(position)
                .findViewById<View>(R.id.editProfile).performClick()
        }
        waitForActivity("Profile editor") { it.findViewById<View>(R.id.configPanel).isShown }
    }

    private fun openNoteDialog(profile: Profile) {
        scenario!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("showProfileNote", Profile::class.java)
                .apply { isAccessible = true }.invoke(activity, profile)
        }
        waitUntil("Note dialog") { dialogOrNull(scenario!!) != null }
        waitUntil("Note dialog laid out") {
            var ready = false
            scenario!!.onActivity { activity ->
                val dialog = dialogOrNull(activity)
                ready = dialog?.isShowing == true && dialog.window?.decorView?.isLaidOut == true
            }
            ready
        }
        instrumentation.waitForIdleSync()
    }

    private fun clipboardText(): String? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString()

    private fun assertVisible(activity: MainActivity, view: View) {
        val name = runCatching { activity.resources.getResourceEntryName(view.id) }.getOrDefault("view-${view.id}")
        val bounds = Rect()
        val visible = view.getGlobalVisibleRect(bounds)
        val location = IntArray(2).also(view::getLocationOnScreen)
        val frame = Rect()
        activity.window.decorView.getWindowVisibleDisplayFrame(frame)
        val details = "$name shown=${view.isShown} attached=${view.isAttachedToWindow} size=${view.width}x${view.height} " +
            "loc=${location[0]},${location[1]} bounds=$bounds visibleFrame=$frame " +
            "dialog=${view.rootView.width}x${view.rootView.height} visibility=${view.visibility}"
        assertTrue("Action is not shown: $details", view.isShown)
        assertTrue("Action has no visible area: $details", visible)
        assertTrue("Action is clipped: $details", bounds.height() > 0)
        assertTrue("Action lies outside the visible frame: $details",
            bounds.top >= frame.top - 2 && bounds.bottom <= frame.bottom + 2)
    }

    private fun assertButtonIntact(activity: MainActivity, button: Button) {
        assertVisible(activity, button)
        assertTrue("Button was compressed below its minimum height: " +
            "height=${button.height}, minimum=${button.minimumHeight}",
            button.height >= button.minimumHeight)
        val textLayout = button.layout
        assertNotNull("Button text has no layout", textLayout)
        val contentHeight = button.height - button.paddingTop - button.paddingBottom
        assertTrue("Button text is clipped: textLayout=${textLayout!!.height}, " +
            "contentHeight=$contentHeight, buttonHeight=${button.height}",
            textLayout.height <= contentHeight + 1)
        assertTrue("Button has no visible label", button.text.isNotEmpty())
    }

    /** Optional checks for external runs under a forced display/font configuration. */
    private fun assertExpectedConfiguration(activity: MainActivity) {
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString("expectFontScale")?.toFloatOrNull()?.let {
            assertEquals("fontScale", it, activity.resources.configuration.fontScale, 0.01f)
        }
        arguments.getString("expectScreenHeightDp")?.toIntOrNull()?.let {
            assertEquals("screenHeightDp", it, activity.resources.configuration.screenHeightDp)
        }
        arguments.getString("expectScreenWidthDp")?.toIntOrNull()?.let {
            assertEquals("screenWidthDp", it, activity.resources.configuration.screenWidthDp)
        }
    }

    private fun load() = ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load()

    private fun dialogOrNull(activity: MainActivity): Dialog? =
        MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity) as Dialog?

    private fun dialogOrNull(scenario: ActivityScenario<MainActivity>): Dialog? {
        var dialog: Dialog? = null
        scenario.onActivity { dialog = dialogOrNull(it) }
        return dialog
    }

    private fun waitForActivity(message: String, check: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario!!.onActivity { ready = check(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("UI did not reach state: $message")
    }

    private fun waitUntil(message: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(50)
        }
        fail("UI did not reach state: $message")
    }
}

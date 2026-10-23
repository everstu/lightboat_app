package com.lightboat.app

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.databinding.ItemProfileBinding
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileCardUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private val preferences get() = context.getSharedPreferences("profile_ui", 0)
    private var originalConfig: ByteArray? = null
    private var originalPreferences = emptyMap<String, Any?>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = if (session.uri.endsWith(".js"))
            newFixedLengthResponse(Response.Status.OK, "application/javascript", "window.cardFixture = true;")
        else newFixedLengthResponse(Response.Status.OK, "text/html", "<!doctype html><title>Card fixture</title><p>Ready</p>")
    }
    @Before fun setUp() {
        originalConfig = configFile.takeIf(File::exists)?.readBytes()
        originalPreferences = preferences.all.toMap()
        preferences.edit().clear().commit()
        server.start()
        val base = "http://127.0.0.1:${server.listeningPort}"
        val library = listOf(SharedScript("s1", "One", "$base/one.js"), SharedScript("s2", "Two", "$base/two.js"))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig("active", listOf(
            Profile("active", "测试网站规则", "$base/", listOf(ProfileScript("s1"))),
            Profile("disabled", "已停用的配置", "$base/disabled", listOf(ProfileScript("s1", false), ProfileScript("s2", false)),
                platform = BrowserPlatform.WINDOWS, categoryId = "work"),
            Profile("plain", "未添加脚本", "$base/plain", platform = BrowserPlatform.APPLE)
        ), mapOf("work" to "工作分类名称比较长"), library))
        scenario = launchConfiguredActivity()
        waitFor {
            var shown = false
            scenario!!.onActivity { shown = it.findViewById<View>(R.id.menuLayer).isShown }
            shown
        }
        scenario!!.onActivity { MainActivity::class.java.getDeclaredMethod("showProfiles").apply { isAccessible = true }.invoke(it) }
        waitFor { var shown = false; scenario!!.onActivity { shown = list(it).findViewHolderForAdapterPosition(0) != null }; shown }
    }
    @After fun tearDown() {
        scenario?.close()
        server.stop()
        originalConfig?.let(configFile::writeBytes) ?: configFile.delete()
        preferences.edit().clear().apply {
            originalPreferences.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value) } }
        }.commit()
    }
    @Test fun compactCardShowsCountsAndCornerStatusWithoutBlockingActions() {
        scenario!!.onActivity { activity ->
            val row = (list(activity).findViewHolderForAdapterPosition(0) as ProfileAdapter.Holder).binding
            assertEquals("脚本：1/1", row.profileScript.text.toString())
            assertEquals("已启用", row.profileScriptStatus.text.toString())
            assertEquals("使用中", row.profileState.text.toString())
            assertTrue(row.root.isActivated)
            assertTrue(row.profileState.isShown)
            assertFalse(row.profileState.isClickable)
            assertTrue(row.profileMeta.bottom <= (row.profileUrl.parent as View).top)
            val target = (48 * activity.resources.displayMetrics.density).toInt()
            listOf(row.openProfile, row.editProfile).forEach {
                assertTrue("An action lost its 48dp target", it.width >= target && it.height >= target)
            }
            assertToolbarRightAligned(row)
        }
        capture()
        tap(R.id.favoriteProfile)
        waitFor { ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load().profiles.first().favorite }
        tap(R.id.profileMore)
        waitFor { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("查看备注")?.any { it.isVisibleToUser } == true }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        tap(R.id.editProfile)
        waitFor { var shown = false; scenario!!.onActivity { shown = it.findViewById<View>(R.id.configPanel).isShown }; shown }
        captureScreen("profile-editor-compact")
        scenario!!.onActivity { activity ->
            activity.findViewById<View>(R.id.addScript).let {
                it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true)
            }
        }
        captureScreen("profile-add-script-compact")
        scenario!!.onActivity { it.findViewById<View>(R.id.cancelButton).performClick() }
        tap(R.id.openProfile)
        waitFor { var shown = false; scenario!!.onActivity { shown = it.findViewById<View>(R.id.menuLayer).isShown }; shown }
    }
    @Test fun recycledCardsClearOldRibbonIconsAndEnabledCounts() {
        scenario!!.onActivity { activity ->
            val adapter = list(activity).adapter as ProfileAdapter
            val holder = adapter.createViewHolder(list(activity), 0)
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.VISIBLE, holder.binding.profileState.visibility)
            adapter.bindViewHolder(holder, 1)
            assertEquals(View.GONE, holder.binding.profileState.visibility)
            assertFalse(holder.binding.root.isActivated)
            assertEquals("脚本：0/2", holder.binding.profileScript.text.toString())
            assertEquals("未启用", holder.binding.profileScriptStatus.text.toString())
            assertNull(holder.binding.profileBadge.compoundDrawablesRelative[0])
            adapter.bindViewHolder(holder, 2)
            assertEquals("脚本：0/0", holder.binding.profileScript.text.toString())
            assertEquals("无脚本", holder.binding.profileScriptStatus.text.toString())
        }
    }
    @Test fun narrowCardsKeepTheWholeCountAndCategoryReadable() {
        scenario!!.onActivity { activity ->
            assertTrue("The manager header squeezed away the list",
                list(activity).height >= (48 * activity.resources.displayMetrics.density).toInt())
            val adapter = list(activity).adapter as ProfileAdapter
            val holder = adapter.createViewHolder(list(activity), 0)
            adapter.bindViewHolder(holder, 1)
            val row = holder.binding
            for (widthDp in listOf(280, 320, 480)) {
                row.root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * activity.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.root.layout(0, 0, row.root.measuredWidth, row.root.measuredHeight)
                assertTrue("Category disappeared at $widthDp dp", row.profileCategory.width > row.profileCategory.compoundPaddingLeft + row.profileCategory.compoundPaddingRight)
                listOf(row.profileScript, row.profileScriptStatus).forEach { text ->
                    assertEquals(1, text.lineCount)
                    assertEquals(0, text.layout.getEllipsisCount(0))
                    assertTrue("Count is clipped at $widthDp dp", text.layout.getLineWidth(0) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                }
                assertTrue(row.profileScriptBadge.right <= row.profileMeta.width)
                assertTrue(row.profileScriptBadge.bottom <= row.profileMeta.height)
            }
        }
    }
    @Test fun floatingStatusTagsDoNotMoveTheToolbarOrCardContent() {
        scenario!!.onActivity { activity ->
            val adapter = list(activity).adapter as ProfileAdapter
            val config = ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load()
            val holder = adapter.createViewHolder(list(activity), 0)
            for (current in listOf("active", null)) {
                for (position in listOf(0, 1)) {
                    val row = holder.binding
                    for (widthDp in listOf(280, 320, 480)) {
                        fun measureCard() {
                            row.root.measure(View.MeasureSpec.makeMeasureSpec(
                                (widthDp * activity.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            row.root.layout(0, 0, row.root.measuredWidth, row.root.measuredHeight)
                        }
                        fun contentBounds() = listOf(row.profileName, row.profileMore.parent as View,
                            row.profileMeta, row.profileUrl, row.openProfile, row.editProfile).map { view ->
                            Rect(0, 0, view.width, view.height).also { row.root.offsetDescendantRectToMyCoords(view, it) }
                        }
                        adapter.submit(config, current)
                        adapter.bindViewHolder(holder, position)
                        measureCard()
                        assertToolbarRightAligned(row)
                        val heightWithTag = row.root.height
                        val boundsWithTag = contentBounds()
                        adapter.submit(config.copy(lastProfileId = null), null)
                        adapter.bindViewHolder(holder, position)
                        measureCard()
                        assertEquals("Status tag changed card height", heightWithTag, row.root.height)
                        assertEquals("Status tag moved card content", boundsWithTag, contentBounds())
                    }
                }
            }
        }
    }
    private fun assertToolbarRightAligned(row: ItemProfileBinding) {
        val density = row.root.resources.displayMetrics.density
        val actions = row.profileMore.parent as View
        assertEquals("Toolbar left an empty right-hand strip", row.profileHeader.width, actions.right)
        assertEquals(row.favoriteProfile.right, row.topProfile.left)
        assertEquals(row.topProfile.right, row.profileMore.left)
        fun iconBounds(action: ImageView): RectF = RectF(0f, 0f,
            action.drawable.intrinsicWidth.toFloat(), action.drawable.intrinsicHeight.toFloat()).also {
            action.imageMatrix.mapRect(it)
            it.offset((actions.left + action.left + action.paddingLeft).toFloat(),
                (actions.top + action.top + action.paddingTop).toFloat())
        }
        listOf(row.favoriteProfile, row.topProfile, row.profileMore).forEach { action ->
            assertEquals("Toolbar did not restore its original spacing", (48 * density).toInt(), action.width)
            assertTrue("Toolbar lost its touch height", action.height >= (48 * density).toInt())
            val bounds = iconBounds(action)
            assertEquals("Toolbar icon was resized", action.drawable.intrinsicWidth.toFloat(), bounds.width(), 1f)
            assertEquals("Toolbar icon was moved down", actions.top + action.top + action.height / 2f, bounds.centerY(), 1f)
        }
        if (row.profileState.visibility == View.VISIBLE) {
            assertEquals("Status tag is not aligned above More", actions.right, row.profileState.right)
            assertTrue("Status tag covers the adjacent action", row.profileState.left >= iconBounds(row.topProfile).right)
            val moreIcon = iconBounds(row.profileMore)
            // The first dot starts at y=4 in the original icon's 24-unit viewport.
            assertTrue("Status tag overlaps the overflow dots: tagBottom=${row.profileState.bottom}, icon=$moreIcon",
                row.profileState.bottom <= moreIcon.top + moreIcon.height() / 6f)
            assertTrue("Status label is clipped", row.profileState.layout.getLineWidth(0) <=
                row.profileState.width - row.profileState.compoundPaddingLeft - row.profileState.compoundPaddingRight + 1)
        }
    }
    private fun list(activity: MainActivity) = activity.findViewById<RecyclerView>(R.id.profilesList)
    private fun tap(id: Int) {
        var x = 0f; var y = 0f
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { activity ->
            val view = list(activity).findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(id)
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        }
        instrumentation.waitForIdleSync()
        waitFor {
            var visible = false
            scenario!!.onActivity { activity ->
                val view = list(activity).findViewHolderForAdapterPosition(0)?.itemView?.findViewById<View>(id)
                if (view != null) {
                    val bounds = Rect()
                    visible = view.getGlobalVisibleRect(bounds) && bounds.height() >= view.height - 1
                }
            }
            visible
        }
        scenario!!.onActivity { activity ->
            val view = list(activity).findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(id)
            val position = IntArray(2).also(view::getLocationOnScreen)
            x = position[0] + view.width / 2f; y = position[1] + view.height / 2f
        }
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event); event.recycle()
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture() {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        SystemClock.sleep(1100) // Let the first frame and transient scroll indicator settle.
        val bounds = Rect()
        scenario!!.onActivity { activity ->
            val card = list(activity).findViewHolderForAdapterPosition(0)!!.itemView
            val position = IntArray(2).also(card::getLocationOnScreen)
            bounds.set(position[0], position[1], position[0] + card.width, position[1] + card.height)
        }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "profile-manager-compact.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val card = android.graphics.Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), minOf(bounds.height(), bitmap.height - bounds.top))
        File(context.getExternalFilesDir(null), "profile-card-compact.png").outputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        card.recycle(); bitmap.recycle()
    }
    private fun captureScreen(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        SystemClock.sleep(1100)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) { if (check()) return; SystemClock.sleep(50) }
        fail("Profile card UI did not reach its expected state")
    }
}

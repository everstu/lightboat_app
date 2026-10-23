package com.lightboat.app

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.CompoundButton
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.databinding.ItemLibraryScriptBinding
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScriptLibraryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private val preferences get() = context.getSharedPreferences("profile_ui", 0)
    private var backup: ByteArray? = null
    private var originalPreferences = emptyMap<String, Any?>()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        backup = configFile.takeIf(File::exists)?.readBytes()
        originalPreferences = preferences.all.toMap()
        preferences.edit().clear().commit()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY)
        scenario = launchConfiguredActivity()
    }
    @After fun tearDown() {
        scenario.close()
        backup?.let(configFile::writeBytes) ?: configFile.delete()
        preferences.edit().clear().apply {
            originalPreferences.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }.commit()
    }

    @Test fun maximumLibraryKeepsActivityStateSmallAndRestoresUnsavedChangesAfterRecreation() {
        val library = (0 until 100).map { index -> SharedScript("large-$index", "Script $index",
            "https://example.com/$index/" + "x".repeat(8000) + ".js") }
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.emptyAddButton).performClick()
            MainActivity::class.java.getDeclaredField("draftScriptLibrary").apply { isAccessible = true }.set(activity, library)
            MainActivity::class.java.getDeclaredField("draftScripts").apply { isAccessible = true }
                .set(activity, listOf(ProfileScript("large-99", false)))
            val state = Bundle()
            MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply { isAccessible = true }.invoke(activity, state)
            val parcel = android.os.Parcel.obtain()
            try { parcel.writeBundle(state); assertTrue("Activity state contains the script library", parcel.dataSize() < 100_000) }
            finally { parcel.recycle() }
            assertNotNull(state.getString("editorDraftId"))
            assertFalse(state.containsKey("draftScriptLibrary"))
        }
        scenario.recreate()
        scenario.onActivity { activity ->
            assertEquals(library, MainActivity::class.java.getDeclaredField("draftScriptLibrary").apply { isAccessible = true }.get(activity))
            assertEquals(listOf(ProfileScript("large-99", false)), MainActivity::class.java.getDeclaredField("draftScripts").apply { isAccessible = true }.get(activity))
            activity.findViewById<View>(R.id.cancelButton).performClick()
        }
        assertTrue(load().scriptLibrary.isEmpty())
    }

    @Test fun corruptConfigurationShowsRecoveryActionsInsteadOfAnApparentlyEmptyLibrary() {
        scenario.close()
        configFile.writeText("{invalid test config")
        try {
            scenario = launchConfiguredActivity()
            scenario.onActivity { activity ->
                assertEquals(context.getString(R.string.config_recovery_title), activity.findViewById<TextView>(R.id.statusTitle).text.toString())
                assertEquals(context.getString(R.string.import_config), activity.findViewById<TextView>(R.id.retryButton).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsButton).visibility)
                activity.findViewById<View>(R.id.retryButton).performClick()
                assertTrue(dialog(activity) is ConfigImportDialog)
                dialog(activity).dismiss()
                activity.findViewById<View>(R.id.editButton).performClick()
                (dialog(activity) as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            assertTrue(load().profiles.isEmpty())
            assertEquals("{invalid test config", File(context.filesDir, "config.invalid.json").readText())
            assertFalse(File(context.filesDir, "config.recovery-required").exists())
        } finally {
            ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY)
            File(context.filesDir, "config.invalid.json").delete()
        }
    }

    @Test fun createsSharedScriptsSelectsOrdersDisablesAndRestoresAnUnsavedProfileDraft() {
        openLibrary()
        create("First script", "https://scripts.example/a.js")
        create("Second script", "https://scripts.example/b.js")
        click("saveLibrary")
        assertEquals(2, load().scriptLibrary.size)
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.emptyAddButton).performClick()
            activity.findViewById<EditText>(R.id.nameInput).setText("Draft profile")
            activity.findViewById<EditText>(R.id.websiteInput).setText("https://page.example/")
            // An equivalent pasted address must reuse the library entry.
            activity.findViewById<EditText>(R.id.scriptInput).setText("HTTPS://SCRIPTS.EXAMPLE:443/a.js")
            activity.findViewById<View>(R.id.addScript).performClick()
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        clickText("Second script")
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val dialog = dialog(activity)
            val list = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)!!
            assertEquals("First script", list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<TextView>(R.id.chooseLibraryScript).text.toString())
            assertEquals("Second script", list.findViewHolderForAdapterPosition(2)!!.itemView.findViewById<TextView>(R.id.chooseLibraryScript).text.toString())
        }
        click("saveLibrary")
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.selectedScripts)
            assertEquals(2, cards.childCount)
            assertFalse(cards.getChildAt(0).findViewById<View>(R.id.selectedScriptUp).isEnabled)
            cards.getChildAt(1).findViewById<View>(R.id.selectedScriptUp).performClick()
            cards.getChildAt(0).findViewById<View>(R.id.enableSelectedScript).performClick()
        }
        assertTrue("Profile draft must not be saved by the script selector", load().profiles.isEmpty())
        scenario.recreate()
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.selectedScripts)
            assertEquals("Second script", cards.getChildAt(0).findViewById<TextView>(R.id.selectedScriptName).text.toString())
            assertFalse(cards.getChildAt(0).findViewById<CompoundButton>(R.id.enableSelectedScript).isChecked)
            assertEquals("First script", cards.getChildAt(1).findViewById<TextView>(R.id.selectedScriptName).text.toString())
            assertTrue(cards.getChildAt(1).findViewById<CompoundButton>(R.id.enableSelectedScript).isChecked)
            activity.findViewById<View>(R.id.saveButton).performClick()
        }
        val saved = load()
        assertEquals(2, saved.scriptLibrary.size)
        assertEquals(listOf("Second script", "First script"), saved.profiles.single().scripts.map { ref -> saved.scriptLibrary.first { it.id == ref.scriptId }.name })
        assertEquals(listOf(false, true), saved.profiles.single().scripts.map { it.enabled })
    }

    @Test fun sharedEditsPersistOnlyAfterSaveAndCancellingDiscardsChanges() {
        val shared = SharedScript("shared", "Common", "https://scripts.example/common.js")
        val config = AppConfig(null, emptyList(), scriptLibrary = listOf(shared))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        scenario.close(); scenario = launchConfiguredActivity()
        openLibrary()
        clickText("Common")
        setText("libraryUrlInput", "https://scripts.example/new.js")
        click("android:id/button1")
        click("cancelLibrary")
        assertEquals(shared.url, load().scriptLibrary.single().url)
        openLibrary()
        clickText("Common")
        setText("libraryUrlInput", "https://scripts.example/new.js")
        click("android:id/button1")
        click("saveLibrary")
        assertEquals("https://scripts.example/new.js", load().scriptLibrary.single().url)
    }

    @Test fun optionalNamesAndMixedBatchInputOnlyPersistWhenTheLibraryIsSaved() {
        openLibrary()
        create("", "https://offline.invalid/unnamed.user.js?token=1")
        assertLibraryOrder(listOf("unnamed.user.js"))
        click("batchAddLibraryScripts")
        setText("batchScriptsInput", "页面增强|https://offline.invalid/enhance.js\nhttps://offline.invalid/style.js\n" +
            "重复地址|HTTPS://OFFLINE.INVALID:443/unnamed.user.js?token=1")
        capture("script-batch")
        click("confirmBatchScripts")
        assertLibraryOrder(listOf("unnamed.user.js", "页面增强", "style.js"))
        assertTrue(load().scriptLibrary.isEmpty())
        click("cancelLibrary")
        assertTrue(load().scriptLibrary.isEmpty())
        openLibrary()
        click("batchAddLibraryScripts")
        setText("batchScriptsInput", "页面增强|https://offline.invalid/enhance.js\nhttps://offline.invalid/style.js")
        click("confirmBatchScripts")
        click("saveLibrary")
        assertEquals(listOf("页面增强", "style.js"), load().scriptLibrary.map { it.name })
        assertTrue(load().profiles.isEmpty())
    }

    @Test fun invalidBatchReportsTheLineAndDoesNotPartiallyAddScripts() {
        openLibrary()
        click("batchAddLibraryScripts")
        setText("batchScriptsInput", "https://offline.invalid/valid.js\nbroken-url")
        click("confirmBatchScripts")
        scenario.onActivity { activity ->
            val editor = ScriptLibraryDialog::class.java.getDeclaredField("editor").apply { isAccessible = true }
                .get(dialog(activity)) as ScriptBatchDialog
            assertTrue(editor.findViewById<EditText>(R.id.batchScriptsInput)!!.error.toString().startsWith("第 2 行："))
        }
        click("cancelBatchScripts")
        click("saveLibrary")
        assertTrue(load().scriptLibrary.isEmpty())
    }

    @Test fun selectionKeepsLibraryOrderAndReselectingPreservesAnExistingDisabledReference() {
        val scripts = listOf(
            SharedScript("ads", "广告过滤 · AdGuard", "https://scripts.example/no-ad.js"),
            SharedScript("style", "页面增强 · LiteStyle", "https://scripts.example/lite-style.js"))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY.copy(scriptLibrary = scripts))
        scenario.close(); scenario = launchConfiguredActivity()
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.emptyAddButton).performClick()
            activity.findViewById<EditText>(R.id.nameInput).setText("日常浏览")
            activity.findViewById<EditText>(R.id.websiteInput).setText("https://page.example/")
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        clickText(scripts[1].name)
        clickText(scripts[0].name)
        assertLibraryOrder(scripts.map { it.name })
        capture("script-selection")
        click("saveLibrary")
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.selectedScripts)
            assertEquals(scripts[1].name, cards.getChildAt(0).findViewById<TextView>(R.id.selectedScriptName).text.toString())
            cards.getChildAt(0).findViewById<View>(R.id.enableSelectedScript).performClick()
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        clickText(scripts[1].name)
        assertLibraryOrder(scripts.map { it.name })
        clickText(scripts[1].name)
        assertLibraryOrder(scripts.map { it.name })
        click("saveLibrary")
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.selectedScripts)
            assertEquals(scripts[0].name, cards.getChildAt(0).findViewById<TextView>(R.id.selectedScriptName).text.toString())
            assertFalse(cards.getChildAt(1).findViewById<CompoundButton>(R.id.enableSelectedScript).isChecked)
            val section = cards.parent as View
            section.requestRectangleOnScreen(android.graphics.Rect(0, 0, section.width,
                activity.findViewById<View>(R.id.chooseScripts).bottom), true)
        }
        capture("profile-scripts")
        scenario.onActivity { it.findViewById<View>(R.id.saveButton).performClick() }
        assertEquals(listOf(ProfileScript("ads"), ProfileScript("style", false)), load().profiles.single().scripts)
        capture("profile-manager")
    }

    @Test fun longLibrariesScrollWithoutHidingTheConfirmationAction() {
        val scripts = (1..40).map { SharedScript("script-$it", "Script $it", "https://scripts.example/$it.js") }
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY.copy(scriptLibrary = scripts))
        scenario.close(); scenario = launchConfiguredActivity()
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.emptyAddButton).performClick()
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        scenario.onActivity { activity ->
            dialog(activity).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)!!.scrollToPosition(scripts.size)
        }
        clickText(scripts.last().name)
        click("saveLibrary")
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.selectedScripts)
            assertEquals(scripts.last().name, cards.getChildAt(0).findViewById<TextView>(R.id.selectedScriptName).text.toString())
        }
    }

    @Test fun quickRemovalOnlyChangesTheCurrentProfileAfterSaving() {
        val library = listOf(
            SharedScript("ad", "no-ad.js", "https://scripts.example/no-ad.js"),
            SharedScript("style", "LiteStyle.user.js", "https://scripts.example/style.js"),
            SharedScript("media", "Media.user.js", "https://scripts.example/media.js"))
        val references = library.map { ProfileScript(it.id) }
        val profiles = listOf(Profile("first", "日常浏览", "https://page.example/", references),
            Profile("second", "另一个配置", "https://other.example/", references))
        launchManager(AppConfig("first", profiles, scriptLibrary = library))
        scenario.onActivity { activity ->
            val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.profilesList)
            list.getChildAt(0).findViewById<View>(R.id.editProfile).performClick()
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.selectedScripts)
            val section = cards.parent as View
            section.requestRectangleOnScreen(android.graphics.Rect(0, 0, section.width,
                activity.findViewById<View>(R.id.chooseScripts).bottom), true)
        }
        capture("script-actions")
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.selectedScripts)
            cards.getChildAt(0).findViewById<View>(R.id.removeSelectedScript).performClick()
            assertEquals(2, cards.childCount)
            assertEquals("LiteStyle.user.js", cards.getChildAt(0).findViewById<TextView>(R.id.selectedScriptName).text.toString())
            assertEquals("1", cards.getChildAt(0).findViewById<TextView>(R.id.selectedScriptOrder).text.toString())
            assertFalse(cards.getChildAt(0).findViewById<View>(R.id.selectedScriptUp).isEnabled)
        }
        assertEquals(references, load().profiles.first().scripts)
        scenario.onActivity { it.findViewById<View>(R.id.saveButton).performClick() }
        val saved = load()
        assertEquals(references.drop(1), saved.profiles.first().scripts)
        assertEquals(references, saved.profiles.last().scripts)
        assertEquals(library, saved.scriptLibrary)
    }

    @Test fun floatingActionsRevealIndividuallyBeforeOpeningAndCollapseWhenIdle() {
        launchManager(AppConfig("one", listOf(Profile("one", "日常浏览", "https://page.example/"))))
        scenario.onActivity { activity ->
            val group = activity.findViewById<LinearLayout>(R.id.profileFloatingActions)
            assertEquals(LinearLayout.VERTICAL, group.orientation)
            assertEquals(0.85f, group.alpha, 0.001f)
            val surface = (activity.findViewById<View>(R.id.scriptLibrary) as android.view.ViewGroup).getChildAt(0)
            assertEquals("Floating background must remain circular", surface.width, surface.height)
            val visual = android.graphics.Rect().also(surface::getGlobalVisibleRect)
            val restingDiameter = (39 * activity.resources.displayMetrics.density).toInt()
            assertEquals(restingDiameter.toDouble(), visual.width().toDouble(), 2.0)
            assertEquals(restingDiameter.toDouble(), visual.height().toDouble(), 2.0)
            assertFalse(activity.findViewById<View>(R.id.scriptLibrary).isSelected)
            assertFalse(activity.findViewById<View>(R.id.addButton).isSelected)
        }
        capture("floating-actions-resting")
        tapFloatingAction(R.id.scriptLibrary)
        scenario.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.scriptLibrary).isSelected)
            assertTrue(activity.findViewById<View>(R.id.profilesPanel).isShown)
        }
        capture("floating-actions-expanded")
        tapViewCenter(R.id.favoriteFilter)
        scenario.onActivity { activity ->
            assertFalse("Outside tap must collapse the expanded button", activity.findViewById<View>(R.id.scriptLibrary).isSelected)
            assertTrue("Outside tap must still reach the original control", activity.findViewById<View>(R.id.favoriteFilter).isSelected)
        }
        tapViewCenter(R.id.favoriteFilter)
        tapFloatingAction(R.id.scriptLibrary)
        tapFloatingAction(R.id.addButton)
        scenario.onActivity { activity ->
            assertFalse(activity.findViewById<View>(R.id.scriptLibrary).isSelected)
            assertTrue(activity.findViewById<View>(R.id.addButton).isSelected)
            assertTrue(activity.findViewById<View>(R.id.profilesPanel).isShown)
        }
        capture("floating-profiles-expanded")
        waitFor {
            var collapsed = false
            scenario.onActivity { collapsed = !it.findViewById<View>(R.id.addButton).isSelected }
            collapsed
        }
        tapFloatingAction(R.id.addButton)
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.profilesPanel).isShown) }
        tapFloatingAction(R.id.addButton)
        scenario.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.configPanel).isShown)
        }
    }

    @Test fun scriptEditorRetainsItsFieldsAndSaveActionAcrossRotationWithTheKeyboard() {
        val automation = instrumentation.uiAutomation
        try {
            assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0))
            openLibrary()
            click("newLibraryScript")
            setText("libraryNameInput", "Rotation draft")
            setText("libraryUrlInput", "https://scripts.example/rotation.js")
            fun showKeyboard() {
                scenario.onActivity { activity ->
                    val editor = ScriptLibraryDialog::class.java.getDeclaredField("editor").apply { isAccessible = true }
                        .get(dialog(activity)) as ScriptEditorDialog
                    val input = editor.findViewById<EditText>(R.id.libraryUrlInput)!!
                    input.requestFocus()
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            showKeyboard()
            assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
            waitFor {
                var landscape = false
                scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                landscape && node("android:id/button1")?.isVisibleToUser == true
            }
            showKeyboard()
            waitFor {
                var visible = false
                scenario.onActivity { activity ->
                    val editor = ScriptLibraryDialog::class.java.getDeclaredField("editor").apply { isAccessible = true }
                        .get(dialog(activity)) as ScriptEditorDialog
                    visible = androidx.core.view.ViewCompat.getRootWindowInsets(editor.window!!.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            capture("script-editor-landscape")
            scenario.onActivity { activity ->
                val editor = ScriptLibraryDialog::class.java.getDeclaredField("editor").apply { isAccessible = true }
                    .get(dialog(activity)) as ScriptEditorDialog
                assertEquals("Rotation draft", editor.findViewById<EditText>(R.id.libraryNameInput)!!.text.toString())
                assertEquals("https://scripts.example/rotation.js", editor.findViewById<EditText>(R.id.libraryUrlInput)!!.text.toString())
                val visible = android.graphics.Rect().also(editor.window!!.decorView::getWindowVisibleDisplayFrame)
                // getGlobalVisibleRect is relative to this dialog's root, while the visible
                // display frame is in screen coordinates (the dialog need not start at 0,0).
                val windowOrigin = IntArray(2).also(editor.window!!.decorView::getLocationOnScreen)
                val saveButton = editor.findViewById<Button>(android.R.id.button1)!!
                val saveBounds = android.graphics.Rect()
                assertTrue("Save action has no visible area", saveButton.getGlobalVisibleRect(saveBounds))
                assertTrue("Save action is clipped", saveBounds.height() >= saveButton.height - 1)
                saveBounds.offset(windowOrigin[0], windowOrigin[1])
                assertTrue("Keyboard covered the save action", visible.contains(saveBounds))
                val input = editor.findViewById<EditText>(R.id.libraryUrlInput)!!
                val inputBounds = android.graphics.Rect()
                assertTrue("Focused input has no visible area", input.getGlobalVisibleRect(inputBounds))
                val hierarchy = generateSequence(input as View) { it.parent as? View }.joinToString(" > ") {
                    "${it.javaClass.simpleName}[h=${it.height},y=${it.top},pad=${it.paddingTop}/${it.paddingBottom},scroll=${it.scrollY}]"
                }
                assertTrue("Focused input is clipped: $inputBounds height=${input.height} frame=$visible window=${editor.window!!.decorView.height} $hierarchy", inputBounds.height() >= input.height - 1)
                inputBounds.offset(windowOrigin[0], windowOrigin[1])
                assertTrue("Focused input is outside the visible screen: $inputBounds in $visible", visible.contains(inputBounds))
            }
            click("android:id/button1")
            click("saveLibrary")
            assertEquals("Rotation draft", load().scriptLibrary.single().name)
        } finally { automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
    }

    @Test fun usageBadgesShowSavedProfileCountsWithGlobalScopeAndMatchingStyles() {
        val library = listOf(
            SharedScript("ads", "Ads.user.js", "https://scripts.example/ads.js"),
            SharedScript("reader", "Reader.user.js", "https://scripts.example/reader.js"),
            SharedScript("unused", "Unused.user.js", "https://scripts.example/unused.js"))
        val profiles = listOf(
            Profile("one", "工作一", "https://page.example/one",
                listOf(ProfileScript("ads", false), ProfileScript("reader")), categoryId = "work", favorite = true),
            Profile("two", "工作二", "https://page.example/two",
                listOf(ProfileScript("ads"), ProfileScript("reader")), categoryId = "work"),
            Profile("three", "工作三", "https://page.example/three",
                listOf(ProfileScript("ads"), ProfileScript("reader")), categoryId = "work"),
            Profile("four", "娱乐一", "https://page.example/four", listOf(ProfileScript("ads")), categoryId = "play"),
            Profile("five", "娱乐二", "https://page.example/five", listOf(ProfileScript("ads")), categoryId = "play"))
        preferences.edit().putString("categoryId", "work").putBoolean("favoritesOnly", true).commit()
        launchManager(AppConfig("one", profiles, mapOf("work" to "工作", "play" to "娱乐"), library))
        // Only one profile is visible, but the badge counts every saved profile in every category.
        scenario.onActivity { activity ->
            assertEquals(1, activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.profilesList)
                .adapter!!.itemCount)
        }
        openLibrary()
        assertUsage(listOf(5, 3, 0))
        capture("script-usage-badges")
        assertLibraryActionsVisible()
        revealLibraryHeaderActions()
        click("newLibraryScript")
        click("android:id/button2")
        click("cancelLibrary")
        scenario.onActivity { activity ->
            val add = activity.findViewById<View>(R.id.addButton)
            if (!add.isSelected) add.performClick()
            add.performClick()
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        assertUsage(listOf(5, 3, 0))
        capture("script-usage-selection")
        assertLibraryActionsVisible()
    }

    @Test fun usageBadgeWrapsBelowTheNameWithoutClippingOrSplittingText() {
        scenario.onActivity { activity ->
            fun row(widthDp: Int, selecting: Boolean, fontScale: Float = 1f): ItemLibraryScriptBinding {
                val density = activity.resources.displayMetrics.density
                val inflated = ItemLibraryScriptBinding.inflate(android.view.LayoutInflater.from(activity))
                inflated.chooseLibraryScript.text = "很长的脚本名称用于测试标题行换行.user.js"
                val checkbox = inflated.chooseLibraryScript.buttonDrawable
                inflated.chooseLibraryScript.buttonDrawable = if (selecting) checkbox else null
                inflated.libraryScriptUsageText.text = context.getString(R.string.script_usage_short, 12)
                if (fontScale != 1f) {
                    // Scale the layout's own sizes, which is what a 2.0x system font does to sp text.
                    inflated.chooseLibraryScript.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        inflated.chooseLibraryScript.textSize * fontScale)
                    inflated.libraryScriptUsageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        inflated.libraryScriptUsageText.textSize * fontScale)
                }
                inflated.root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                inflated.root.layout(0, 0, inflated.root.measuredWidth, inflated.root.measuredHeight)
                return inflated
            }
            for (selecting in listOf(false, true)) {
                val wide = row(360, selecting)
                assertFalse("wide selecting=$selecting", wide.libraryScriptTitle.badgeWrapped)
                assertEquals(1, wide.libraryScriptUsageText.lineCount)
                assertTrue(wide.chooseLibraryScript.right <= wide.libraryScriptUsage.left)
                assertTrue(wide.libraryScriptUsage.right <= wide.libraryMore.left)
                assertTrue(wide.libraryScriptUsageText.height <= wide.libraryScriptUsage.height)
            }
            // Selection mode must account for the checkbox before deciding to wrap.
            val narrow = row(240, true)
            assertTrue("A narrow selecting row must wrap the whole badge", narrow.libraryScriptTitle.badgeWrapped)
            assertEquals(1, narrow.libraryScriptUsageText.lineCount)
            assertEquals(0, narrow.libraryScriptUsage.left)
            assertTrue(narrow.libraryScriptUsage.top >= narrow.chooseLibraryScript.bottom)
            assertTrue(narrow.libraryScriptUsage.right <= narrow.root.width)
            assertEquals(1, narrow.chooseLibraryScript.lineCount)
            assertEquals((48 * activity.resources.displayMetrics.density).toInt(), narrow.libraryMore.width)
            // 2.0x font grows the badge height instead of clipping its single line.
            val scaled = row(360, false, fontScale = 2f)
            assertTrue("Large-font rows must wrap the badge", scaled.libraryScriptTitle.badgeWrapped)
            assertEquals(1, scaled.libraryScriptUsageText.lineCount)
            assertTrue(scaled.libraryScriptUsageText.height <= scaled.libraryScriptUsage.height)
            assertTrue(scaled.libraryScriptUsage.height >= (24 * activity.resources.displayMetrics.density).toInt())
        }
    }

    @Test fun usageCountChangesOnlyAfterSavingTheProfileDraft() {
        val script = SharedScript("shared", "Shared.user.js", "https://scripts.example/shared.js")
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY.copy(scriptLibrary = listOf(script)))
        scenario.close(); scenario = launchConfiguredActivity()
        fun assertCount(count: Int) {
            waitFor { node("libraryScriptUsageText")?.text?.toString() == context.getString(R.string.script_usage_short, count) }
        }
        fun newProfile() = scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.emptyAddButton).performClick()
            activity.findViewById<EditText>(R.id.nameInput).setText("Draft profile")
            activity.findViewById<EditText>(R.id.websiteInput).setText("https://page.example/")
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        newProfile()
        clickText(script.name)
        assertCount(0)
        click("saveLibrary")
        scenario.onActivity { it.findViewById<View>(R.id.chooseScripts).performClick() }
        assertCount(0)
        click("cancelLibrary")
        scenario.onActivity { it.findViewById<View>(R.id.cancelButton).performClick() }
        assertTrue(load().profiles.isEmpty())
        newProfile()
        clickText(script.name)
        click("saveLibrary")
        scenario.onActivity { it.findViewById<View>(R.id.saveButton).performClick() }
        openLibrary()
        assertCount(1)
        click("cancelLibrary")
        scenario.onActivity { activity ->
            activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.profilesList)
                .getChildAt(0).findViewById<View>(R.id.editProfile).performClick()
            activity.findViewById<View>(R.id.chooseScripts).performClick()
        }
        clickText(script.name)
        assertCount(1)
        click("saveLibrary")
        scenario.onActivity { it.findViewById<View>(R.id.chooseScripts).performClick() }
        assertCount(1)
        click("saveLibrary")
        scenario.onActivity { it.findViewById<View>(R.id.saveButton).performClick() }
        openLibrary()
        assertCount(0)
        assertTrue(load().profiles.single().scripts.isEmpty())
    }

    @Test fun recycledUsageBadgesKeepTheirSavedCountsAndStyles() {
        val library = (0 until 40).map { SharedScript("script-$it", "Script $it", "https://scripts.example/$it.js") }
        val profiles = library.filterIndexed { index, _ -> index % 2 == 0 }.chunked(10).mapIndexed { index, scripts ->
            Profile("profile-$index", "Profile $index", "https://page.example/",
                scripts.map { ProfileScript(it.id, false) })
        }
        launchManager(AppConfig(profiles.first().id, profiles, scriptLibrary = library))
        openLibrary()
        val counts = library.indices.map { if (it % 2 == 0) 1 else 0 }
        assertUsage(counts)
        // Traverse again after the bottom rows have recycled the original holders.
        assertUsage(counts)
    }

    private fun assertUsage(counts: List<Int>) {
        waitFor {
            var ready = false
            scenario.onActivity { activity ->
                val list = dialog(activity).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)
                ready = list?.adapter?.itemCount == counts.size + 1
            }
            ready
        }
        val fills = arrayOfNulls<android.content.res.ColorStateList>(counts.size)
        counts.indices.forEach { index ->
            // Large fonts can push rows out of the viewport; scroll each one in before asserting.
            waitFor {
                var ready = false
                scenario.onActivity { activity ->
                    val list = dialog(activity).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)!!
                    if (list.findViewHolderForAdapterPosition(index + 1) != null) ready = true
                    else list.scrollToPosition(index + 1)
                }
                ready
            }
            scenario.onActivity { activity ->
                val list = dialog(activity).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)!!
                val row = list.findViewHolderForAdapterPosition(index + 1)!!.itemView
                val count = counts[index]
                val badge = row.findViewById<LinearLayout>(R.id.libraryScriptUsage)
                val text = row.findViewById<TextView>(R.id.libraryScriptUsageText)
                val icon = row.findViewById<android.widget.ImageView>(R.id.libraryScriptUsageIcon)
                val color = activity.getColor(if (count > 0) R.color.accent else R.color.muted)
                assertEquals(activity.getString(R.string.script_usage_short, count), text.text.toString())
                assertEquals(activity.getString(R.string.script_usage, count), text.contentDescription.toString())
                assertEquals(color, text.currentTextColor)
                assertEquals(color, ImageViewCompat.getImageTintList(icon)!!.defaultColor)
                val iconSize = (14 * activity.resources.displayMetrics.density).toInt()
                assertTrue(Math.abs(icon.drawable.intrinsicWidth - iconSize) <= 1)
                assertTrue(Math.abs(icon.drawable.intrinsicHeight - iconSize) <= 1)
                val background = badge.background
                assertTrue("The usage badge needs a pill background",
                    background is android.graphics.drawable.GradientDrawable)
                fills[index] = (background as android.graphics.drawable.GradientDrawable).color
                assertEquals("Badge text must stay on one line", 1, text.lineCount)
                assertTrue("Badge text is clipped", text.height <= badge.height)
                assertEquals("The more action must keep its 48dp target",
                    (48 * activity.resources.displayMetrics.density).toInt(), row.findViewById<View>(R.id.libraryMore).width)
            }
        }
        val usedFills = counts.indices.filter { counts[it] > 0 }.map { fills[it] }.toSet()
        val unusedFills = counts.indices.filter { counts[it] == 0 }.map { fills[it] }.toSet()
        assertEquals("Referenced rows must share the used pill", 1, usedFills.size)
        assertEquals("Unreferenced rows must share the unused pill", 1, unusedFills.size)
        assertNotEquals("A zero-reference row must not keep the used pill", usedFills.single(), unusedFills.single())
        scenario.onActivity { activity ->
            dialog(activity).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)
                ?.scrollToPosition(1)
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertLibraryActionsVisible() = scenario.onActivity { activity ->
        val library = dialog(activity)
        val frame = android.graphics.Rect().also(library.window!!.decorView::getWindowVisibleDisplayFrame)
        val origin = IntArray(2).also(library.window!!.decorView::getLocationOnScreen)
        for (id in listOf(R.id.cancelLibrary, R.id.saveLibrary)) {
            val button = library.findViewById<Button>(id)!!
            val bounds = android.graphics.Rect()
            assertTrue(button.getGlobalVisibleRect(bounds))
            assertEquals("The library action is clipped: ${button.text} $bounds root=${library.window!!.decorView.height} y=${button.top}", button.height, bounds.height())
            bounds.offset(origin[0], origin[1])
            assertTrue("The library action is outside the screen: $bounds in $frame", frame.contains(bounds))
            assertTrue("The action text is clipped", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
        }
    }

    private fun revealLibraryHeaderActions() {
        scenario.onActivity { dialog(it).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)!!.scrollToPosition(0) }
        waitFor {
            var attached = false
            scenario.onActivity { attached = dialog(it).findViewById<View>(R.id.newLibraryScript) != null }
            attached
        }
        scenario.onActivity { activity ->
            val button = dialog(activity).findViewById<View>(R.id.newLibraryScript)!!
            button.requestRectangleOnScreen(android.graphics.Rect(0, 0, button.width, button.height), true)
        }
        instrumentation.waitForIdleSync()
        waitFor { node("newLibraryScript")?.isVisibleToUser == true && node("batchAddLibraryScripts")?.isVisibleToUser == true }
    }

    private fun tapFloatingAction(id: Int) {
        var x = 0f; var y = 0f
        scenario.onActivity { activity ->
            val view = activity.findViewById<View>(id)
            val position = IntArray(2).also(view::getLocationOnScreen)
            val density = activity.resources.displayMetrics.density
            // Tap within the 48 dp target but outside the small resting background.
            x = position[0] + view.width - 44 * density
            y = position[1] + 8 * density
        }
        sendTap(x, y)
    }

    private fun tapViewCenter(id: Int) {
        var x = 0f; var y = 0f
        scenario.onActivity { activity ->
            val view = activity.findViewById<View>(id)
            val position = IntArray(2).also(view::getLocationOnScreen)
            x = position[0] + view.width / 2f
            y = position[1] + view.height / 2f
        }
        sendTap(x, y)
    }

    private fun sendTap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun openLibrary() = scenario.onActivity { activity ->
        val button = activity.findViewById<View>(R.id.scriptLibrary)
        if (!button.isSelected) button.performClick()
        button.performClick()
    }

    private fun launchManager(config: AppConfig) {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
        scenario.close(); scenario = launchConfiguredActivity()
        scenario.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("showProfiles").apply { isAccessible = true }.invoke(activity)
        }
        waitFor {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.findViewById<View>(R.id.profilesPanel).isShown &&
                    activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.profilesList).adapter!!.itemCount > 0
            }
            ready
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertLibraryOrder(names: List<String>) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val list = dialog(activity).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.libraryList)!!
            assertEquals(names, (0 until list.childCount).mapNotNull {
                list.getChildAt(it).findViewById<TextView>(R.id.chooseLibraryScript)?.text?.toString()
            })
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.waitForIdleSync()
        SystemClock.sleep(if (name == "profile-manager") 2500 else 300) // Allow windows and save toast to settle.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun load() = ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load()
    private fun dialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity) as Dialog
    private fun create(name: String, url: String) {
        click("newLibraryScript"); setText("libraryNameInput", name); setText("libraryUrlInput", url); click("android:id/button1")
    }
    private fun node(id: String) = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(
        if (id.startsWith("android:")) id else "${context.packageName}:id/$id")?.firstOrNull { it.isVisibleToUser }
    private fun setText(id: String, text: String) {
        waitFor { node(id) != null }
        assertTrue(node(id)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun click(id: String) { waitFor { node(id)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }; instrumentation.waitForIdleSync() }
    private fun clickText(text: String) {
        waitFor { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull {
            it.text?.toString() == text && it.isVisibleToUser
        }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        instrumentation.waitForIdleSync()
    }
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) { if (check()) return; SystemClock.sleep(50) }
        capture("script-ui-failure")
        fail("Script library UI did not reach expected state " + DebugLog.readAfter(null).text.takeLast(1800))
    }
}

package com.lightboat.app

import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import com.lightboat.app.databinding.DialogEditScriptBinding
import java.util.UUID

/** Scroll the form while keeping actions visible above a landscape keyboard. */
internal class ScriptEditorDialog(
    activity: AppCompatActivity,
    private val script: SharedScript?,
    private val save: (SharedScript) -> Unit,
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogEditScriptBinding.inflate(activity.layoutInflater)
    private var compact = false
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener {
        updateLayout()
        if (compact) binding.libraryEditorScroll.post { revealFocusedInput() }
    }

    private fun revealFocusedInput() {
        val input = currentFocus?.takeIf { it === binding.libraryNameInput || it === binding.libraryUrlInput } ?: return
        val scroll = binding.libraryEditorScroll
        if (input.height > scroll.height || scroll.height == 0) return
        val viewport = IntArray(2).also(scroll::getLocationOnScreen)
        val field = IntArray(2).also(input::getLocationOnScreen)
        val delta = when {
            field[1] < viewport[1] -> field[1] - viewport[1]
            field[1] + input.height > viewport[1] + scroll.height -> field[1] + input.height - viewport[1] - scroll.height
            else -> 0
        }
        if (delta != 0) scroll.scrollBy(0, delta)
    }

    @Suppress("DEPRECATION") // Floating AppCompat windows use adjustResize on API 26+.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        // A focus-scroll animation can finish after the IME resize and overwrite our full-field
        // reveal with a stale offset. Keep focus scrolling immediate; touch scrolling still works.
        binding.libraryEditorScroll.isSmoothScrollingEnabled = false
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        binding.libraryEditorTitle.setText(if (script == null) R.string.script_new else R.string.edit)
        ViewCompat.setAccessibilityHeading(binding.libraryEditorTitle, true)
        binding.libraryNameInput.setText(script?.name.orEmpty())
        binding.libraryUrlInput.setText(script?.url.orEmpty())
        findViewById<android.view.View>(android.R.id.button2)!!.setOnClickListener { dismiss() }
        findViewById<android.view.View>(android.R.id.button1)!!.setOnClickListener {
            val url = try { AppConfig.normalizeScriptUrl(binding.libraryUrlInput.text.toString()) }
            catch (error: IllegalArgumentException) { binding.libraryUrlInput.error = error.message; binding.libraryUrlInput.requestFocus(); return@setOnClickListener }
            val name = try { AppConfig.normalizeScriptName(binding.libraryNameInput.text.toString(), url) }
            catch (error: IllegalArgumentException) { binding.libraryNameInput.error = error.message; binding.libraryNameInput.requestFocus(); return@setOnClickListener }
            try {
                save(SharedScript(script?.id ?: UUID.randomUUID().toString(), name, url))
                dismiss()
            } catch (error: IllegalArgumentException) { binding.libraryUrlInput.error = error.message }
        }
    }

    private fun updateSpacing(keyboardVisible: Boolean) {
        val next = keyboardVisible && context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (compact == next) return
        compact = next
        val density = context.resources.displayMetrics.density
        val side = context.resources.getDimensionPixelSize(R.dimen.ui_page_inset)
        val vertical = if (compact) (2 * density).toInt() else side
        binding.root.setPadding(side, vertical, side, vertical)
        listOf(binding.libraryNameInput, binding.libraryUrlInput).forEach { input ->
            input.minimumHeight = context.resources.getDimensionPixelSize(R.dimen.ui_touch_target)
            val padding = ((if (compact) 8 else 12) * density).toInt()
            val horizontal = context.resources.getDimensionPixelSize(R.dimen.ui_field_inset)
            input.setPadding(horizontal, padding, horizontal, padding)
        }
        (binding.libraryEditorActions.layoutParams as ViewGroup.MarginLayoutParams).apply {
            topMargin = ((if (compact) 2 else 8) * density).toInt()
            binding.libraryEditorActions.layoutParams = this
        }
        binding.root.doOnNextLayout {
            currentFocus?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(layoutChanged)
        updateLayout()
    }
    override fun onStop() {
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(layoutChanged)
        super.onStop()
    }
    override fun updateLayout() {
        val dialogWindow = window ?: return
        val visible = Rect().also(dialogWindow.decorView::getWindowVisibleDisplayFrame)
        val width = DialogLayout.width(context)
        // A floating window can consume IME insets before they reach the content.
        // Its visible display frame already excludes the keyboard and system bars.
        updateSpacing(context.resources.displayMetrics.heightPixels - visible.bottom > 100 *
            context.resources.displayMetrics.density)
        val height = if (compact) visible.height().coerceAtLeast(1) else WindowManager.LayoutParams.WRAP_CONTENT
        if (dialogWindow.attributes.width != width || dialogWindow.attributes.height != height) {
            binding.root.doOnNextLayout {
                currentFocus?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
            }
            dialogWindow.setLayout(width, height)
        }
    }
}

package com.lightboat.app

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.lightboat.app.databinding.DialogRenameCategoryBinding

/**
 * Category management: rename the selected category or delete it after confirmation.
 * The Activity owns persistence; this window only requests a change and closes on success.
 */
internal class CategoryRenameDialog(
    private val activity: AppCompatActivity,
    private val config: AppConfig,
    initialId: String?,
    private val nameFilter: CategoryNameInputFilter,
    private val save: (AppConfig) -> Boolean,
    private val delete: (String) -> Boolean
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogRenameCategoryBinding.inflate(activity.layoutInflater)
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener { updateLayout() }
    private var categoryId = initialId?.takeIf { config.categories.containsKey(it) } ?: config.categories.keys.first()
    private var confirmation: AppCompatDialog? = null
    private var chromeWidth = -1
    private var chromeScale = 0f
    private var chromeHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this AppCompat adds an empty action bar that would squeeze the action row.
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        // Set once: changing the soft-input mode from a layout callback would relayout forever.
        @Suppress("DEPRECATION")
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setCanceledOnTouchOutside(true)
        ViewCompat.setAccessibilityHeading(binding.renameTitle, true)
        ViewCompat.setAccessibilityPaneTitle(binding.root, context.getString(R.string.category_rename_title))
        binding.categoryNameInput.filters = arrayOf(InputFilter.LengthFilter(AppConfig.MAX_CATEGORY_NAME), nameFilter)
        binding.categoryNameInput.doAfterTextChanged { binding.categoryNameInput.error = null }
        showCategory()
        binding.selectCategory.setOnClickListener {
            val choices = config.categories.map { (id, name) ->
                CategoryChoice(id, name, config.profiles.count { it.categoryId == id })
            }
            showCategoryChoices(binding.selectCategory, choices, categoryId) { choice ->
                categoryId = requireNotNull(choice.id)
                showCategory()
                binding.root.post { if (isShowing) updateLayout() }
            }
        }
        binding.closeRename.setOnClickListener { dismiss() }
        binding.cancelRename.setOnClickListener { dismiss() }
        binding.saveRename.setOnClickListener { saveName() }
        binding.deleteCategory.setOnClickListener { confirmDelete() }
        binding.categoryNameInput.setOnEditorActionListener { _, _, _ -> saveName(); true }
    }

    private fun showCategory() {
        val name = config.categories.getValue(categoryId)
        binding.selectCategory.text = name
        binding.categoryNameInput.setText(name)
        binding.categoryNameInput.setSelection(binding.categoryNameInput.length())
        binding.renameHint.text = context.getString(R.string.category_rename_hint, config.profiles.count { it.categoryId == categoryId })
    }

    private fun saveName() {
        val next = try {
            config.renameCategory(categoryId, binding.categoryNameInput.text.toString())
        } catch (error: IllegalArgumentException) {
            binding.categoryNameInput.error = error.message
            binding.categoryNameInput.requestFocus()
            return
        }
        if (!nameFilter.fits(next.categories.getValue(categoryId))) {
            binding.categoryNameInput.error = context.getString(R.string.category_name_too_wide)
            binding.categoryNameInput.requestFocus()
            return
        }
        if (save(next)) dismiss()
    }

    private fun confirmDelete() {
        if (!config.categories.containsKey(categoryId)) return
        hideKeyboard()
        // The saved name and saved references decide; an unsaved rename never applies here.
        val name = config.categories.getValue(categoryId)
        val count = config.profiles.count { it.categoryId == categoryId }
        val target = categoryId
        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Lightboat_DestructiveAlert)
            .setTitle(R.string.category_delete_title)
            .setMessage(if (count > 0) context.getString(R.string.category_delete_message_with_profiles, name, count)
                else context.getString(R.string.category_delete_message_empty, name))
            .setNegativeButton(R.string.import_cancel, null)
            .setPositiveButton(R.string.category_delete_confirm, null)
            .create()
        // The default positive button dismisses itself; only a saved delete may close the windows.
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).apply {
                setTextColor(context.getColor(R.color.error))
                setOnClickListener {
                    if (delete(target)) {
                        dialog.dismiss()
                        dismiss()
                    }
                }
            }
        }
        showConfirmation(dialog)
    }

    private fun showConfirmation(dialog: AppCompatDialog) {
        confirmation?.dismiss()
        confirmation = dialog
        dialog.setOnDismissListener { if (confirmation === dialog) confirmation = null }
        dialog.show()
    }

    private fun hideKeyboard() {
        binding.categoryNameInput.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.categoryNameInput.windowToken, 0)
    }

    override fun onStart() {
        super.onStart()
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(layoutChanged)
        updateLayout()
    }

    override fun onStop() {
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(layoutChanged)
        confirmation?.dismiss()
        super.onStop()
    }

    override fun updateLayout() {
        val metrics = context.resources.displayMetrics
        val visibleHeight = DialogLayout.visibleHeight(window)
            .takeIf { it >= DialogLayout.dp(context, 160) && it <= metrics.heightPixels }
            ?: metrics.heightPixels
        // The extra visible-frame cap keeps the dialog on screen and clear of a shown keyboard.
        val maximumDialog = minOf((metrics.heightPixels * 0.86f).toInt(), visibleHeight - DialogLayout.dp(context, 16))
            .coerceAtLeast(DialogLayout.dp(context, 160))
        val width = DialogLayout.width(context, maxWidthDp = 440, marginDp = 32)
        // Keep the delete area scrollable while the title and both action rows stay reachable.
        val chrome = chromeHeightFor(width)
        binding.renameScroll.maximumHeight = (maximumDialog - chrome - DialogLayout.dp(context, 4)).coerceAtLeast(1)
        DialogLayout.apply(window, binding.root, width, maximumDialog)
    }

    /**
     * Fixed chrome (title and both action rows) for [width]. Cached because the temporary
     * [BoundedScrollView.maximumHeight] change requests a layout, and re-measuring on every
     * global layout would otherwise loop.
     */
    private fun chromeHeightFor(width: Int): Int {
        // scaledDensity covers both display density and font-scale changes.
        val scale = context.resources.displayMetrics.scaledDensity
        if (width == chromeWidth && scale == chromeScale) return chromeHeight
        binding.renameScroll.maximumHeight = 1
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        chromeHeight = (binding.root.measuredHeight - binding.renameScroll.measuredHeight).coerceAtLeast(0)
        chromeWidth = width
        chromeScale = scale
        return chromeHeight
    }
}

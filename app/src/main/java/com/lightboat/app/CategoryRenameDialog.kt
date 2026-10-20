package com.lightboat.app

import android.os.Bundle
import android.text.InputFilter
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.lightboat.app.databinding.DialogRenameCategoryBinding

internal class CategoryRenameDialog(
    activity: AppCompatActivity,
    private val config: AppConfig,
    initialId: String?,
    private val nameFilter: CategoryNameInputFilter,
    private val save: (AppConfig) -> Boolean
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogRenameCategoryBinding.inflate(activity.layoutInflater)
    private var categoryId = initialId?.takeIf { config.categories.containsKey(it) } ?: config.categories.keys.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
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
            }
        }
        binding.closeRename.setOnClickListener { dismiss() }
        binding.cancelRename.setOnClickListener { dismiss() }
        binding.saveRename.setOnClickListener { saveName() }
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

    override fun onStart() {
        super.onStart()
        updateLayout()
    }

    override fun updateLayout() {
        window?.apply {
            setLayout(DialogLayout.width(context, maxWidthDp = 440, marginDp = 32), WindowManager.LayoutParams.WRAP_CONTENT)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
    }
}

package com.lightboat.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.lightboat.app.databinding.ItemCategoryFilterBinding

internal data class CategoryChoice(val id: String?, val name: String, val count: Int? = null)

internal fun bindProfileCategoryFilter(
    button: Button, config: AppConfig, filter: ProfileFilter, changed: (ProfileFilter) -> Unit
) {
    val context = button.context
    button.text = when (filter.categoryId) {
        null -> context.getString(R.string.category_all)
        "" -> context.getString(R.string.category_none)
        else -> config.categories[filter.categoryId]
    }
    button.contentDescription = context.getString(R.string.category_filter_description, button.text)
    button.setOnClickListener {
        val choices = (listOf(null to context.getString(R.string.category_all),
            "" to context.getString(R.string.category_none)) + config.categories.entries.map { it.key to it.value })
            .map { (id, name) -> CategoryChoice(id, name, filter.copy(categoryId = id).apply(config).size) }
        showCategoryChoices(button, choices, filter.categoryId) { changed(filter.copy(categoryId = it.id)) }
    }
}

internal fun showCategoryChoices(anchor: View, choices: List<CategoryChoice>, selectedId: String?, select: (CategoryChoice) -> Unit) {
    val context = anchor.context
    ListPopupWindow(context).apply {
        anchorView = anchor
        width = anchor.width
        isModal = true
        verticalOffset = (6 * context.resources.displayMetrics.density).toInt()
        setBackgroundDrawable(AppCompatResources.getDrawable(context, R.drawable.bg_category_popup))
        setListSelector(ColorDrawable(Color.TRANSPARENT))
        setAdapter(object : BaseAdapter() {
            override fun getCount() = choices.size
            override fun getItem(position: Int) = choices[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = if (convertView == null) ItemCategoryFilterBinding.inflate(LayoutInflater.from(context), parent, false)
                    else ItemCategoryFilterBinding.bind(convertView)
                val choice = choices[position]
                val selected = selectedId == choice.id
                row.root.isActivated = selected
                row.categoryName.text = choice.name
                row.categoryName.setTextColor(context.getColor(if (selected) R.color.accent else R.color.ink))
                row.categoryName.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                row.categoryCount.text = choice.count?.toString().orEmpty()
                row.categoryCount.visibility = if (choice.count == null) View.GONE else View.VISIBLE
                row.root.contentDescription = if (choice.count == null) choice.name
                    else context.getString(R.string.category_filter_option, choice.name, choice.count)
                ViewCompat.setStateDescription(row.root, if (selected) context.getString(R.string.category_selected) else null)
                return row.root
            }
        })
        setOnItemClickListener { _, _, position, _ ->
            dismiss()
            select(choices[position])
        }
        show()
        choices.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { setSelection(it) }
    }
}

internal fun bindProfileFavoriteFilter(button: Button, filter: ProfileFilter, changed: (ProfileFilter) -> Unit) {
    button.isSelected = filter.favoritesOnly
    button.setCompoundDrawablesRelativeWithIntrinsicBounds(
        if (filter.favoritesOnly) R.drawable.ic_star else R.drawable.ic_star_outline, 0, 0, 0)
    TextViewCompat.setCompoundDrawableTintList(button,
        AppCompatResources.getColorStateList(button.context, R.color.config_segment_text))
    ViewCompat.setStateDescription(button, button.context.getString(
        if (filter.favoritesOnly) R.string.favorites_filter_on else R.string.favorites_filter_off))
    button.setOnClickListener { changed(filter.copy(favoritesOnly = !filter.favoritesOnly)) }
}

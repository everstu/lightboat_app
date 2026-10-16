package com.lightboat.app

import android.widget.Button
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.TextViewCompat

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
        PopupMenu(context, button).apply {
            val choices = listOf(null to context.getString(R.string.category_all),
                "" to context.getString(R.string.category_none)) + config.categories.entries.map { it.key to it.value }
            choices.forEachIndexed { index, (id, name) ->
                val count = filter.copy(categoryId = id).apply(config).size
                menu.add(0, index, index, context.getString(R.string.category_filter_option, name, count)).apply {
                    isCheckable = true
                    isChecked = filter.categoryId == id
                    setOnMenuItemClickListener { changed(filter.copy(categoryId = id)); true }
                }
            }
            menu.setGroupCheckable(0, true, true)
        }.show()
    }
}

internal fun bindProfileFavoriteFilter(button: Button, filter: ProfileFilter, changed: (ProfileFilter) -> Unit) {
    button.isSelected = filter.favoritesOnly
    button.setCompoundDrawablesRelativeWithIntrinsicBounds(
        if (filter.favoritesOnly) R.drawable.ic_star else R.drawable.ic_star_outline, 0, 0, 0)
    TextViewCompat.setCompoundDrawableTintList(button,
        AppCompatResources.getColorStateList(button.context, R.color.config_segment_text))
    androidx.core.view.ViewCompat.setStateDescription(button, button.context.getString(
        if (filter.favoritesOnly) R.string.favorites_filter_on else R.string.favorites_filter_off))
    button.setOnClickListener { changed(filter.copy(favoritesOnly = !filter.favoritesOnly)) }
}

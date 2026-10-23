package com.lightboat.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.lightboat.app.databinding.ItemProfileBinding

/** Keeps a small icon beside its label in a stretched button; compound drawables pin it to the edge. */
internal fun iconLabel(context: Context, label: Int, icon: Int): CharSequence {
    val drawable = requireNotNull(AppCompatResources.getDrawable(context, icon))
    val size = (16 * context.resources.displayMetrics.density).toInt()
    drawable.setBounds(0, 0, size, size)
    return SpannableString("\uFFFC  ${context.getString(label)}").apply {
        setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

class ProfileAdapter(
    private val open: (Profile) -> Unit,
    private val edit: (Profile) -> Unit,
    private val delete: (Profile) -> Unit,
    private val favorite: (Profile) -> Unit,
    private val moveToTop: (Profile) -> Unit,
    private val showNote: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    class Holder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)
    private val profiles = mutableListOf<Profile>()
    private var lastId: String? = null
    private var currentId: String? = null
    private var categories: Map<String, String> = emptyMap()
    val orderedIds get() = profiles.map { it.id }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(config: AppConfig, current: String?, visible: List<Profile> = config.profiles) {
        profiles.clear()
        profiles.addAll(visible)
        categories = config.categories
        lastId = config.lastProfileId
        currentId = current
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in profiles.indices || to !in profiles.indices || from == to) return false
        profiles.add(to, profiles.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    override fun getItemCount() = profiles.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        row.root.clipToOutline = true
        row.openProfile.text = iconLabel(parent.context, R.string.switch_profile, R.drawable.ic_profile_open)
        row.editProfile.text = iconLabel(parent.context, R.string.edit, R.drawable.ic_profile_edit)
        return Holder(row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val profile = profiles[position]
        val row = holder.binding
        val context = row.root.context
        row.profileName.text = profile.name
        val category = categories[profile.categoryId] ?: context.getString(R.string.category_none)
        row.profileCategory.text = category
        row.profileCategory.contentDescription = context.getString(R.string.profile_category, category)
        row.profileCategory.tooltipText = category
        row.favoriteProfile.setImageResource(if (profile.favorite) R.drawable.ic_star else R.drawable.ic_star_outline)
        row.favoriteProfile.contentDescription = context.getString(
            if (profile.favorite) R.string.profile_unfavorite_description else R.string.profile_favorite_description, profile.name)
        row.favoriteProfile.setOnClickListener { favorite(profile) }
        row.topProfile.contentDescription = context.getString(R.string.profile_top_description, profile.name)
        row.topProfile.tooltipText = context.getString(R.string.profile_move_top)
        row.topProfile.setOnClickListener { moveToTop(profile) }
        row.root.isActivated = profile.id == currentId
        row.profileUrl.text = profile.url.substringBefore('?').substringBefore('#')
        val enabledScripts = profile.scripts.count { it.enabled }
        val summary = context.getString(R.string.profile_scripts_compact, enabledScripts, profile.scripts.size)
        row.profileScript.text = SpannableString(summary).apply {
            val start = summary.indexOf('：') + 1
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(context.getColor(R.color.ink)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        row.profileScript.contentDescription = context.getString(R.string.profile_scripts_count, enabledScripts, profile.scripts.size)
        row.profileScriptStatus.setText(when {
            profile.scripts.isEmpty() -> R.string.profile_scripts_absent
            enabledScripts == 0 -> R.string.profile_scripts_off
            else -> R.string.profile_scripts_enabled
        })
        row.profileScriptStatus.setTextColor(context.getColor(if (enabledScripts > 0) R.color.accent else R.color.muted))
        row.profileBadge.text = context.getString(when (profile.platform) {
                BrowserPlatform.APPLE -> R.string.platform_apple
                BrowserPlatform.ANDROID -> R.string.platform_android
                BrowserPlatform.WINDOWS -> R.string.platform_windows
            })
        row.profileBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (profile.platform == BrowserPlatform.ANDROID) R.drawable.ic_profile_android else 0, 0, 0, 0)
        row.profileState.text = when (profile.id) {
            currentId -> context.getString(R.string.current_profile)
            lastId -> context.getString(R.string.last_profile)
            else -> ""
        }
        val hasState = row.profileState.text.isNotEmpty()
        row.profileState.visibility = if (hasState) View.VISIBLE else View.GONE
        row.profileState.isActivated = row.root.isActivated
        val stateColor = context.getColor(if (row.root.isActivated) R.color.accent else R.color.muted)
        row.profileState.setTextColor(stateColor)
        row.profileState.background.mutate().apply { setTint(stateColor); alpha = 96 }
        row.openProfile.contentDescription = context.getString(R.string.profile_open_description, profile.name)
        row.editProfile.contentDescription = context.getString(R.string.profile_edit_description, profile.name)
        row.openProfile.setOnClickListener { open(profile) }
        row.editProfile.setOnClickListener { edit(profile) }
        row.profileMore.setOnClickListener {
            PopupMenu(context, row.profileMore).apply {
                menu.add(R.string.view_note).setOnMenuItemClickListener { showNote(profile); true }
                menu.add(R.string.delete).setOnMenuItemClickListener { delete(profile); true }
            }.show()
        }
    }
}

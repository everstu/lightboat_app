package com.lightboat.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.lightboat.app.databinding.ItemProfileBinding

class ProfileAdapter(
    private val open: (Profile) -> Unit,
    private val edit: (Profile) -> Unit,
    private val delete: (Profile) -> Unit,
    private val startDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    class Holder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)
    private val profiles = mutableListOf<Profile>()
    private var lastId: String? = null
    private var currentId: String? = null
    val orderedIds get() = profiles.map { it.id }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(config: AppConfig, current: String?) {
        profiles.clear()
        profiles.addAll(config.profiles)
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
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val profile = profiles[position]
        val row = holder.binding
        val context = row.root.context
        row.profileName.text = profile.name
        row.root.isActivated = profile.id == currentId
        row.profileUrl.text = profile.url.substringBefore('?').substringBefore('#')
        row.profileScript.text = if (profile.scriptUrl.isBlank()) context.getString(R.string.profile_script_none)
        else context.getString(R.string.profile_script_host,
            profile.scriptUrl.substringBefore('?').substringBefore('#'))
        row.profileBadge.text = context.getString(when (profile.platform) {
                BrowserPlatform.APPLE -> R.string.platform_apple
                BrowserPlatform.ANDROID -> R.string.platform_android
                BrowserPlatform.WINDOWS -> R.string.platform_windows
            })
        row.profileState.text = when (profile.id) {
            currentId -> context.getString(R.string.current_profile)
            lastId -> context.getString(R.string.last_profile)
            else -> ""
        }
        row.profileState.visibility = if (row.profileState.text.isEmpty()) View.GONE else View.VISIBLE
        row.openProfile.contentDescription = context.getString(R.string.profile_open_description, profile.name)
        row.editProfile.contentDescription = context.getString(R.string.profile_edit_description, profile.name)
        row.openProfile.setOnClickListener { open(profile) }
        row.editProfile.setOnClickListener { edit(profile) }
        row.profileMore.setOnClickListener {
            PopupMenu(context, row.profileMore).apply {
                menu.add(R.string.delete).setOnMenuItemClickListener { delete(profile); true }
            }.show()
        }
        row.dragHandle.setOnLongClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && profiles.size > 1) {
                startDrag(holder)
                true
            } else false
        }
    }
}

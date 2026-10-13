package com.lightboat.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        row.profileUrl.text = profile.url
        row.profileBadge.text = listOfNotNull(
            context.getString(R.string.last_profile).takeIf { profile.id == lastId },
            context.getString(R.string.current_profile).takeIf { profile.id == currentId }
        ).joinToString(" · ")
        row.profileBadge.visibility = if (row.profileBadge.text.isEmpty()) View.GONE else View.VISIBLE
        row.openProfile.setOnClickListener { open(profile) }
        row.editProfile.setOnClickListener { edit(profile) }
        row.deleteProfile.setOnClickListener { delete(profile) }
        row.dragHandle.setOnLongClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && profiles.size > 1) {
                startDrag(holder)
                true
            } else false
        }
    }
}

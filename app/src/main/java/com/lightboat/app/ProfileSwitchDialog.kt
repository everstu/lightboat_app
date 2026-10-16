package com.lightboat.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightboat.app.databinding.DialogSwitchProfileBinding
import com.lightboat.app.databinding.ItemSwitchProfileBinding

internal class ProfileSwitchDialog(
    private val activity: AppCompatActivity,
    private val config: AppConfig,
    private val currentId: String?,
    private val open: (Profile) -> Unit,
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogSwitchProfileBinding.inflate(activity.layoutInflater)
    private val entries = SwitchAdapter { profile -> dismiss(); open(profile) }
    private val profiles get() = config.profiles
    private var filter = ProfileFilter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        ViewCompat.setAccessibilityHeading(binding.switchTitle, true)
        ViewCompat.setAccessibilityPaneTitle(binding.root, context.getString(R.string.switch_configuration))
        binding.switchSummary.text = context.getString(R.string.profile_switch_summary, profiles.size)
        binding.switchList.layoutManager = LinearLayoutManager(context)
        binding.switchList.adapter = entries
        binding.switchList.itemAnimator = null
        binding.searchPanel.visibility = if (profiles.size > 5) View.VISIBLE else View.GONE
        binding.closeSwitch.setOnClickListener { dismiss() }
        binding.clearSearch.setOnClickListener { binding.searchProfiles.text?.clear() }
        binding.resetFilters.setOnClickListener {
            filter = ProfileFilter()
            binding.searchProfiles.text?.clear()
            refreshProfiles()
        }
        binding.searchProfiles.doAfterTextChanged { text ->
            binding.clearSearch.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            refreshProfiles()
        }
        binding.searchProfiles.setOnEditorActionListener { _, _, _ ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.root.windowToken, 0)
            binding.root.requestFocus()
            true
        }
        refreshProfiles()
        binding.root.requestFocus()
    }

    private fun changeFilter(next: ProfileFilter) {
        filter = next.resolve(config)
        refreshProfiles()
        binding.switchList.scrollToPosition(0)
    }

    private fun refreshProfiles() {
        bindProfileCategoryFilter(binding.categoryFilter, config, filter, ::changeFilter)
        bindProfileFavoriteFilter(binding.favoriteFilter, filter, ::changeFilter)
        val visible = filter.apply(config, binding.searchProfiles.text.toString())
        entries.show(visible)
        binding.switchSummary.text = if (filter == ProfileFilter() && binding.searchProfiles.text.isNullOrBlank())
            context.getString(R.string.profile_switch_summary, profiles.size)
        else context.getString(R.string.profiles_filtered_count, visible.size, profiles.size)
        binding.noProfilesFound.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.switchList.visibility = if (visible.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        updateLayout()
        filter.apply(config, binding.searchProfiles.text.toString()).indexOfFirst { it.id == currentId }.takeIf { it >= 0 }?.let {
            binding.switchList.scrollToPosition(it)
        }
    }

    override fun updateLayout() {
        val compact = activity.resources.configuration.screenHeightDp < 480
        binding.switchSummary.visibility = if (compact) View.GONE else View.VISIBLE
        binding.startupNote.visibility = if (compact) View.GONE else View.VISIBLE
        val density = context.resources.displayMetrics.density
        binding.root.setPadding(0, ((if (compact) 12 else 20) * density).toInt(), 0, 0)
        binding.switchFooter.setPadding((20 * density).toInt(), ((if (compact) 8 else 12) * density).toInt(),
            (20 * density).toInt(), ((if (compact) 12 else 20) * density).toInt())
        entries.setCompact(compact)
        val width = minOf(context.resources.displayMetrics.widthPixels - (32 * density).toInt(), (560 * density).toInt())
        val safeInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val safeHeight = activity.resources.displayMetrics.heightPixels -
            (safeInsets?.top ?: 0) - (safeInsets?.bottom ?: 0)
        val dialogHeight = (safeHeight * 0.9f).toInt()
        binding.root.maximumHeight = dialogHeight
        window?.apply {
            setGravity(Gravity.CENTER)
            attributes = attributes.apply { y = 0 }
            setLayout(width, dialogHeight)
            @Suppress("DEPRECATION") // Floating dialogs also need IME resizing on Android 8–10.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
    }

    private inner class SwitchAdapter(private val select: (Profile) -> Unit) : RecyclerView.Adapter<SwitchAdapter.Holder>() {
        inner class Holder(val row: ItemSwitchProfileBinding) : RecyclerView.ViewHolder(row.root)
        private var visible = emptyList<Profile>()
        private var compactRows = false

        @SuppressLint("NotifyDataSetChanged")
        fun setCompact(compact: Boolean) {
            if (compactRows == compact) return
            compactRows = compact
            notifyDataSetChanged()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun show(profiles: List<Profile>) {
            visible = profiles
            notifyDataSetChanged()
        }

        override fun getItemCount() = visible.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSwitchProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val profile = visible[position]
            val row = holder.row
            val current = profile.id == currentId
            val host = profile.url.toUri().authority ?: profile.url
            val platform = context.getString(when (profile.platform) {
                BrowserPlatform.APPLE -> R.string.platform_apple
                BrowserPlatform.ANDROID -> R.string.platform_android
                BrowserPlatform.WINDOWS -> R.string.platform_windows
            })
            row.root.isActivated = current
            row.root.isSelected = current
            val density = context.resources.displayMetrics.density
            val verticalPadding = ((if (compactRows) 12 else 16) * density).toInt()
            row.root.setPadding((16 * density).toInt(), verticalPadding, (16 * density).toInt(), verticalPadding)
            row.root.minimumHeight = ((if (compactRows) 72 else 96) * density).toInt()
            row.profileMeta.visibility = if (compactRows) View.GONE else View.VISIBLE
            row.profileInitial.text = profile.name.substring(0, profile.name.offsetByCodePoints(0, 1)).uppercase()
            row.profileName.text = profile.name
            row.profileHost.text = host
            val category = config.categories[profile.categoryId] ?: context.getString(R.string.category_none)
            row.profilePlatform.text = context.getString(R.string.profile_category_platform, platform, category)
            row.profileFavorite.visibility = if (profile.favorite) View.VISIBLE else View.GONE
            row.profileState.setText(R.string.current_profile)
            row.profileState.visibility = if (current) View.VISIBLE else View.GONE
            row.profileIndicator.setImageResource(if (current) R.drawable.ic_check_circle else R.drawable.ic_chevron)
            row.root.contentDescription = context.getString(R.string.profile_switch_description, profile.name, host,
                row.profilePlatform.text) + if (profile.favorite) context.getString(R.string.profile_favorite_suffix) else ""
            ViewCompat.setStateDescription(row.root, if (current) context.getString(R.string.current_profile) else null)
            row.root.setOnClickListener { select(profile) }
        }
    }
}

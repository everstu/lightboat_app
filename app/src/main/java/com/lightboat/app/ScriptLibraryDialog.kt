package com.lightboat.app

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightboat.app.databinding.DialogScriptLibraryBinding
import com.lightboat.app.databinding.ItemLibraryHeaderBinding
import com.lightboat.app.databinding.ItemLibraryScriptBinding

/** Library edits and profile selection are drafts until the enclosing Save succeeds. */
internal class ScriptLibraryDialog(
    private val activity: AppCompatActivity,
    private val config: AppConfig,
    references: List<ProfileScript>?,
    private val save: (List<SharedScript>, List<ProfileScript>) -> Boolean,
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogScriptLibraryBinding.inflate(activity.layoutInflater)
    private val selecting = references != null
    private val initialReferences = references.orEmpty().associateBy { it.scriptId }
    private var library = config.scriptLibrary.toList()
    private val selected = references.orEmpty().toMutableList()
    private val adapter = ScriptAdapter()
    private val headerAdapter = HeaderAdapter()
    private var editor: AppCompatDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.libraryTitle, true)
        binding.libraryTitle.setText(if (selecting) R.string.script_select else R.string.script_library)
        binding.saveLibrary.setText(if (selecting) R.string.script_confirm_selection else R.string.save_profile)
        binding.libraryList.layoutManager = LinearLayoutManager(context)
        // Let help and creation actions scroll with the entries on short, large-font screens.
        // Only the title and final save/cancel actions reserve fixed vertical space.
        binding.libraryList.adapter = ConcatAdapter(headerAdapter, adapter)
        binding.libraryList.itemAnimator = null
        binding.cancelLibrary.setOnClickListener { dismiss() }
        binding.saveLibrary.setOnClickListener { if (save(library, selected.toList())) dismiss() }
        refresh()
    }

    private fun edit(script: SharedScript?) {
        showEditor(ScriptEditorDialog(activity, script) { updated ->
            library = AppConfig.upsertScript(library, updated)
            refresh()
        })
    }

    private fun remove(script: SharedScript) {
        val count = config.scriptReferenceCount(script.id)
        val dialog = AlertDialog.Builder(context, R.style.ThemeOverlay_Lightboat_DestructiveAlert).setTitle(R.string.script_delete)
            .setMessage(context.getString(R.string.script_delete_message, script.name, count))
            .setNegativeButton(R.string.import_cancel, null).setPositiveButton(R.string.delete) { _, _ ->
                library = library.filterNot { it.id == script.id }
                selected.removeAll { it.scriptId == script.id }
                refresh()
            }.create()
        showEditor(dialog)
    }

    private fun showEditor(dialog: AppCompatDialog) {
        editor?.dismiss()
        editor = dialog
        dialog.setOnDismissListener { if (editor === dialog) editor = null }
        dialog.show()
        updateEditorLayout()
    }

    private fun updateEditorLayout() {
        val dialog = editor?.takeIf { it.isShowing } ?: return
        if (dialog is ResponsiveDialog) { dialog.updateLayout(); return }
        dialog.window?.setLayout(DialogLayout.width(context), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        headerAdapter.notifyItemChanged(0)
        adapter.notifyDataSetChanged()
        if (isShowing) binding.root.post { if (isShowing) updateLayout() }
    }

    override fun onStart() { super.onStart(); updateLayout() }
    override fun onStop() { editor?.dismiss(); super.onStop() }
    override fun updateLayout() {
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.86f).toInt()
        DialogLayout.apply(window, binding.root, DialogLayout.width(context), maxHeight)
        updateEditorLayout()
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.Holder>() {
        inner class Holder(val header: ItemLibraryHeaderBinding) : RecyclerView.ViewHolder(header.root)
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val header = ItemLibraryHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            header.libraryHelp.text = context.getString(
                if (selecting) R.string.script_select_help else R.string.script_library_help,
                context.getString(R.string.script_usage_help))
            header.newLibraryScript.setOnClickListener { edit(null) }
            header.batchAddLibraryScripts.setOnClickListener {
                showEditor(ScriptBatchDialog(activity) { text ->
                    val result = ScriptBatchInput.addTo(library, text)
                    library = result.library
                    refresh()
                    android.widget.Toast.makeText(context, context.getString(R.string.script_batch_result,
                        result.added, result.duplicates), android.widget.Toast.LENGTH_SHORT).show()
                })
            }
            return Holder(header)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.header.emptyScriptLibrary.visibility = if (library.isEmpty()) View.VISIBLE else View.GONE
            holder.header.newLibraryScript.isEnabled = library.size < AppConfig.MAX_LIBRARY_SCRIPTS
            holder.header.batchAddLibraryScripts.isEnabled = library.size < AppConfig.MAX_LIBRARY_SCRIPTS
        }
    }

    private inner class ScriptAdapter : RecyclerView.Adapter<ScriptAdapter.Holder>() {
        inner class Holder(val row: ItemLibraryScriptBinding) : RecyclerView.ViewHolder(row.root) {
            val selectionDrawable = row.chooseLibraryScript.buttonDrawable
        }
        override fun getItemCount() = library.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemLibraryScriptBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val script = library[position]
            val index = selected.indexOfFirst { it.scriptId == script.id }
            val row = holder.row
            row.chooseLibraryScript.setOnCheckedChangeListener(null)
            row.chooseLibraryScript.text = script.name
            row.chooseLibraryScript.isChecked = index >= 0
            row.root.isActivated = selecting && index >= 0
            row.chooseLibraryScript.buttonDrawable = if (selecting) holder.selectionDrawable else null
            row.chooseLibraryScript.setOnClickListener {
                if (!selecting) { edit(script); return@setOnClickListener }
                val current = selected.indexOfFirst { it.scriptId == script.id }
                if (current >= 0) selected.removeAt(current)
                else if (selected.size < AppConfig.MAX_PROFILE_SCRIPTS) selected.add(initialReferences[script.id] ?: ProfileScript(script.id))
                else android.widget.Toast.makeText(context, context.getString(R.string.script_profile_limit, AppConfig.MAX_PROFILE_SCRIPTS), android.widget.Toast.LENGTH_LONG).show()
                notifyItemChanged(holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener)
            }
            row.libraryScriptUrl.text = script.url.substringBefore('?').substringBefore('#')
            bindUsage(row, script)
            row.libraryScriptUrl.setPadding(if (selecting) (36 * context.resources.displayMetrics.density).toInt() else
                (12 * context.resources.displayMetrics.density).toInt(), 0, (8 * context.resources.displayMetrics.density).toInt(), 0)
            row.chooseLibraryScript.contentDescription = if (selecting) context.getString(R.string.script_library_select_description, script.name) else script.name
            row.root.setOnClickListener { row.chooseLibraryScript.performClick() }
            row.libraryMore.setOnClickListener {
                PopupMenu(context, row.libraryMore).apply {
                    menu.add(R.string.edit).setOnMenuItemClickListener { edit(script); true }
                    menu.add(R.string.script_delete).setOnMenuItemClickListener { remove(script); true }
                }.show()
            }
        }
    }

    /** Every bind rewrites text, color, icon and background so recycled rows cannot mix states. */
    private fun bindUsage(row: ItemLibraryScriptBinding, script: SharedScript) {
        val count = config.scriptReferenceCount(script.id)
        val used = count > 0
        val color = context.getColor(if (used) R.color.accent else R.color.muted)
        row.libraryScriptUsageText.text = context.getString(R.string.script_usage_short, count)
        row.libraryScriptUsageText.setTextColor(color)
        row.libraryScriptUsageText.contentDescription = context.getString(R.string.script_usage, count)
        row.libraryScriptUsageIcon.setImageResource(
            if (used) R.drawable.ic_script_usage_linked else R.drawable.ic_script_usage_none)
        ImageViewCompat.setImageTintList(row.libraryScriptUsageIcon, ColorStateList.valueOf(color))
        row.libraryScriptUsage.setBackgroundResource(
            if (used) R.drawable.bg_script_usage_positive else R.drawable.bg_script_usage_zero)
    }
}

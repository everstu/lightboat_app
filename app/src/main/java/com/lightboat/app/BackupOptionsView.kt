package com.lightboat.app

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.lightboat.app.backup.BackupOptions
import com.lightboat.app.databinding.ViewBackupOptionsBinding

class BackupOptionsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val binding = ViewBackupOptionsBinding.inflate(LayoutInflater.from(context), this, true)
    private var available = BackupOptions.ALL
    internal var options: BackupOptions
        get() = BackupOptions(binding.backupScriptData.isChecked, binding.backupPreferences.isChecked).restrictTo(available)
        set(value) {
            val selection = value.restrictTo(available)
            binding.backupScriptData.isChecked = selection.scriptData
            binding.backupPreferences.isChecked = selection.preferences
        }
    init { orientation = VERTICAL }
    internal fun configure(selection: BackupOptions, included: BackupOptions = BackupOptions.ALL, restore: Boolean = false) {
        available = included
        options = selection
        binding.backupOptionsNote.setText(if (restore) R.string.backup_restore_options_note else R.string.backup_options_note)
        setControlsEnabled(true)
    }
    internal fun setControlsEnabled(enabled: Boolean) {
        binding.backupScriptData.isEnabled = enabled && available.scriptData
        binding.backupPreferences.isEnabled = enabled && available.preferences
    }
}

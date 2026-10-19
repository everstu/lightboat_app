package com.lightboat.app.backup

import android.content.Context

internal class BackupOptionsStore(context: Context) {
    private val preferences = context.getSharedPreferences("backup-options", Context.MODE_PRIVATE)
    fun load() = BackupOptions(preferences.getBoolean("scriptData", true), preferences.getBoolean("preferences", true))
    fun save(options: BackupOptions) {
        preferences.edit().putBoolean("scriptData", options.scriptData).putBoolean("preferences", options.preferences).apply()
    }
}

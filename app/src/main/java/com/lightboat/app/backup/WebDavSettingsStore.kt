package com.lightboat.app.backup

import android.content.Context

/** Server credentials stay in app-private preferences and are never part of a backup. */
internal class WebDavSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("webdav", Context.MODE_PRIVATE)
    fun load() = WebDavSettings(preferences.getString("directory", "").orEmpty(),
        preferences.getString("username", "").orEmpty(), preferences.getString("password", "").orEmpty())
    fun save(settings: WebDavSettings) {
        val previous = load()
        val editor = preferences.edit().putString("directory", settings.directory)
            .putString("username", settings.username).putString("password", settings.password)
        if (previous != settings) editor.remove("etag")
        if (previous.directory != settings.directory || previous.username != settings.username)
            editor.remove("uploadedAt").remove("restoredAt")
        check(editor.commit()) { "无法保存 WebDAV 设置" }
    }
    fun acceptedVersion() = preferences.getString("etag", null)
    fun accept(etag: String?, restored: Boolean) {
        check(preferences.edit().putString("etag", etag).putLong(if (restored) "restoredAt" else "uploadedAt", System.currentTimeMillis()).commit()) {
            "操作已完成，但无法保存本机同步记录"
        }
    }
    fun uploadedAt() = preferences.getLong("uploadedAt", 0)
    fun restoredAt() = preferences.getLong("restoredAt", 0)
}

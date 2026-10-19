package com.lightboat.app.backup

import org.json.JSONArray

/** Configuration and the shared script library are always included together. */
internal data class BackupOptions(val scriptData: Boolean = true, val preferences: Boolean = true) {
    fun restrictTo(available: BackupOptions) = BackupOptions(scriptData && available.scriptData, preferences && available.preferences)
    fun components() = JSONArray().put("configuration").apply {
        if (scriptData) put("scriptData")
        if (preferences) put("preferences")
    }
    companion object {
        val ALL = BackupOptions()
        val CONFIG_ONLY = BackupOptions(false, false)
        fun parse(components: JSONArray): BackupOptions {
            val names = (0 until components.length()).map { components.getString(it) }
            require(names.size == names.toSet().size && names.all { it in setOf("configuration", "scriptData", "preferences") } && "configuration" in names) {
                "备份组件清单无效或缺少配置和脚本库"
            }
            return BackupOptions("scriptData" in names, "preferences" in names)
        }
    }
}

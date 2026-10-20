package com.lightboat.app.backup

import com.lightboat.app.ConfigCodec
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A small journal plus directory renames keeps config, GM data and UI preferences together. */
internal class BackupTransaction(private val root: File, private val writePreferences: (BackupPreferences) -> Unit) {
    private val directory = File(root, "webdav-restore-transaction")
    private val marker get() = File(directory, "state.json")
    private val values get() = File(root, "userscript-values")

    fun install(candidate: OpenedBackup, oldConfig: String, oldPreferences: BackupPreferences, options: BackupOptions = candidate.info.options,
        oldConfigRecovery: Boolean = false): File? {
        check(!directory.exists()) { "上一次恢复尚未完成，请重新启动应用" }
        check(directory.mkdirs()) { "无法创建恢复事务目录" }
        atomicWrite(File(directory, "old-config.json"), oldConfig)
        atomicWrite(File(directory, "old-preferences.json"), oldPreferences.json().toString())
        atomicWrite(marker, JSONObject().put("state", "pending").put("configRecovery", oldConfigRecovery).toString())
        try {
            if (options.scriptData) {
                check(values.exists() || values.mkdirs()) { "无法准备脚本存储目录" }
                check(values.renameTo(File(directory, "old-values"))) { "无法保留当前脚本数据" }
                check(File(candidate.directory, "userscript-values").renameTo(values)) { "无法恢复脚本数据" }
            }
            replaceConfig(ConfigCodec.encode(candidate.config))
            if (options.preferences) writePreferences(candidate.preferences)
            atomicWrite(marker, JSONObject().put("state", "committed").toString())
        } catch (error: Exception) {
            try { recover()?.deleteRecursively() }
            catch (rollback: Exception) { error.addSuppressed(rollback); throw IllegalStateException("恢复未完成，请重新启动应用以还原数据", error) }
            throw error
        }
        return retire()
    }

    /** Must run before ConfigStore.load and before creating any userscript hosts. No large copies. */
    fun recover(): File? {
        if (!directory.exists()) return null
        if (marker.exists()) {
            val journal = JSONObject(BackupArchive.readText(marker, 4096))
            val state = journal.getString("state")
            require(state in setOf("pending", "committed")) { "恢复事务状态损坏" }
            if (state == "pending") {
                val text = BackupArchive.readText(File(directory, "old-config.json"), 1024 * 1024)
                ConfigCodec.decode(text)
                val prefs = BackupPreferences.parse(BackupArchive.readText(File(directory, "old-preferences.json"), 1024 * 1024))
                val previous = File(directory, "old-values")
                if (previous.exists()) {
                    if (values.exists()) check(values.renameTo(File(directory, "discarded-${UUID.randomUUID()}"))) { "无法移出未完成恢复的数据" }
                    check(previous.renameTo(values)) { "无法还原脚本数据" }
                }
                replaceConfig(text)
                // EMPTY is only a rollback placeholder when the original config
                // was unreadable; do not present it as successfully recovered data.
                if (journal.optBoolean("configRecovery", false)) {
                    atomicWrite(File(root, "config.recovery-required"), "config.invalid.json")
                    for (suffix in listOf("", ".bak", ".new")) {
                        val placeholder = File(root, "config.json$suffix")
                        check(!placeholder.exists() || placeholder.delete()) { "无法还原配置恢复状态" }
                    }
                }
                writePreferences(prefs)
                atomicWrite(marker, JSONObject().put("state", "committed").toString())
            }
        }
        return retire()
    }

    private fun replaceConfig(text: String) {
        // AtomicFile's old sidecars must not resurrect pre-restore data on the next read.
        for (suffix in listOf(".bak", ".new")) {
            val sidecar = File(root, "config.json$suffix")
            check(!sidecar.exists() || sidecar.delete()) { "无法清理旧配置事务" }
        }
        atomicWrite(File(root, "config.json"), text)
    }
    private fun retire(): File? {
        val retired = File(root, "webdav-retired-${UUID.randomUUID()}")
        return if (directory.renameTo(retired)) retired else null
    }

    companion object {
        /**
         * A directory without a pending journal marker only contains preparation
         * files. It is safe to discard on the current process path; callers should
         * block startup only when the live file swap may have started.
         */
        fun requiresRecovery(root: File): Boolean {
            val marker = File(root, "webdav-restore-transaction/state.json")
            if (!marker.exists()) return false
            return try {
                JSONObject(BackupArchive.readText(marker, 4096)).getString("state") != "committed"
            } catch (_: Exception) {
                // A damaged marker cannot prove that the swap was never started.
                true
            }
        }

        fun atomicWrite(file: File, text: String) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".pending")
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            replaceFile(temporary, file)
        }
        fun replaceFile(source: File, destination: File) {
            try { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}

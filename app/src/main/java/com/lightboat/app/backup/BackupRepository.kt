package com.lightboat.app.backup

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import com.lightboat.app.ConfigCodec
import com.lightboat.app.ConfigStore
import com.lightboat.app.AppConfig
import com.lightboat.app.userscript.ScriptStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal class BackupRepository(context: Context, private val defaultUserAgent: String) {
    private val app = context.applicationContext
    private val root = app.filesDir
    fun create(destination: File, workspace: File, options: BackupOptions = BackupOptions.ALL): BackupInfo = ScriptStorage.exclusive {
        val payload = File(workspace, "snapshot-${UUID.randomUUID()}")
        try {
            snapshot(payload, options)
            BackupArchive.write(payload, destination, "${Build.MANUFACTURER} ${Build.MODEL}", version(), options, ::validateStore)
        } finally { payload.deleteRecursively() }
    }

    fun open(archive: File, destination: File) = BackupArchive.open(archive, destination, ::validateStore)

    fun restore(candidate: OpenedBackup, options: BackupOptions = candidate.info.options, allowedToStart: () -> Boolean = { true }): AppConfig {
        synchronized(restoreMonitor) { activeRestores++ }
        try {
            return ScriptStorage.exclusive {
                // A destroyed dialog may still have work waiting behind old GM writes.
                check(allowedToStart()) { "操作已取消" }
                restoreExclusive(candidate, options.restrictTo(candidate.info.options))
            }
        } finally {
            synchronized(restoreMonitor) { activeRestores--; restoreMonitor.notifyAll() }
        }
    }

    private fun restoreExclusive(candidate: OpenedBackup, options: BackupOptions): AppConfig {
        // Validate the selected data again before the transactional file replacement.
        if (options.scriptData) candidate.directory.resolve("userscript-values").listFiles().orEmpty().forEach(::validateStore)
        val transaction = BackupTransaction(root) { writePreferences(app, it) }
        transaction.recover()?.deleteRecursively()
        val currentConfig = ConfigCodec.encode(ConfigStore(app, defaultUserAgent).load())
        val currentPreferences = readPreferences(app)
        transaction.install(candidate, currentConfig, currentPreferences, options)?.deleteRecursively()
        return ConfigStore(app, defaultUserAgent).load()
    }

    private fun snapshot(payload: File, options: BackupOptions = BackupOptions.ALL) {
        check(payload.mkdirs()) { "无法创建备份临时目录" }
        File(payload, "config.json").writeText(ConfigCodec.encode(ConfigStore(app, defaultUserAgent).load()), Charsets.UTF_8)
        if (options.preferences) File(payload, "preferences.json").writeText(readPreferences(app).json().toString(), Charsets.UTF_8)
        if (!options.scriptData) return
        val destination = File(payload, "userscript-values").apply { mkdirs() }
        val source = File(root, "userscript-values")
        // Includes legacy/orphaned valid stores, because identities cannot be reconstructed from config alone.
        val names = source.listFiles().orEmpty().mapNotNull { file ->
            Regex("([a-f0-9]{64}\\.json)(?:\\.bak)?").matchEntire(file.name)?.groupValues?.get(1)
        }.distinct().sorted()
        require(names.size <= BackupArchive.MAX_STORES) { "脚本存储文件过多" }
        var total = 0L
        names.forEach { name ->
            val target = File(destination, name)
            AtomicFile(File(source, name)).openRead().use { input ->
                FileOutputStream(target).use { output ->
                    total += BackupArchive.copyBounded(input, output, BackupArchive.MAX_STORE_BYTES)
                    require(total <= BackupArchive.MAX_TOTAL_BYTES) { "脚本存储总量超过备份上限" }
                    output.fd.sync()
                }
            }
            validateStore(target)
        }
    }

    @Suppress("DEPRECATION")
    private fun version(): String {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "${info.versionName} ($code)"
    }

    companion object {
        private val restoreMonitor = Object()
        private var activeRestores = 0
        val isRestoring: Boolean get() = synchronized(restoreMonitor) { activeRestores != 0 }
        /** Background-only barrier used by a replacement Activity before it loads config. */
        fun awaitRestore() {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            var interrupted = false
            try {
                synchronized(restoreMonitor) {
                    while (activeRestores != 0) {
                        try { restoreMonitor.wait() }
                        catch (_: InterruptedException) { interrupted = true }
                    }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }

        fun readPreferences(context: Context): BackupPreferences {
            val prefs = context.getSharedPreferences("profile_ui", Context.MODE_PRIVATE)
            return BackupPreferences(prefs.getString("categoryId", null), prefs.getBoolean("favoritesOnly", false))
        }
        private fun writePreferences(context: Context, value: BackupPreferences) {
            check(context.getSharedPreferences("profile_ui", Context.MODE_PRIVATE).edit()
                .putString("categoryId", value.categoryId).putBoolean("favoritesOnly", value.favoritesOnly).commit()) { "无法保存恢复后的界面偏好" }
        }
        fun recoverPending(context: Context): File? = BackupTransaction(context.filesDir) { writePreferences(context, it) }.recover()

        /** Capture old temporary directories and the retired automatic undo backup. */
        fun abandonedFiles(context: Context): List<File> {
            val suffix = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
            return context.cacheDir.listFiles().orEmpty().filter { Regex("(?:webdav|local-backup)-$suffix").matches(it.name) } +
                context.filesDir.listFiles().orEmpty().filter { Regex("webdav-retired-$suffix").matches(it.name) } +
                listOfNotNull(File(context.filesDir, "webdav-local").takeIf { it.exists() })
        }

        fun validateStore(file: File) {
            require(file.isFile && file.length() <= BackupArchive.MAX_STORE_BYTES) { "脚本存储超过 10 MiB" }
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                file.inputStream().use { input ->
                    JsonReader(java.io.InputStreamReader(input, decoder)).use { reader ->
                        val keys = mutableSetOf<String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(key.length <= 512 && keys.add(key))
                            reader.beginObject()
                            var type: String? = null
                            val fields = mutableSetOf<String>()
                            while (reader.hasNext()) {
                                val field = reader.nextName()
                                require(fields.add(field))
                                if (field == "type") type = reader.nextString() else reader.skipValue()
                            }
                            reader.endObject()
                            require(type in setOf("json", "undefined"))
                        }
                        reader.endObject(); require(reader.peek() == JsonToken.END_DOCUMENT)
                    }
                }
            } catch (error: Exception) { throw IllegalArgumentException("备份中的脚本存储格式无效", error) }
        }
    }
}

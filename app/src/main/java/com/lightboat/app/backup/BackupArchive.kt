package com.lightboat.app.backup

import com.lightboat.app.AppConfig
import com.lightboat.app.ConfigCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class BackupPreferences(val categoryId: String?, val favoritesOnly: Boolean) {
    fun json() = JSONObject().put("categoryId", categoryId ?: JSONObject.NULL).put("favoritesOnly", favoritesOnly)
    companion object {
        fun parse(text: String): BackupPreferences {
            val value = JSONObject(text)
            val category = if (value.isNull("categoryId")) null else value.get("categoryId").let {
                require(it is String && it.length <= 100) { "备份中的分类偏好无效" }; it
            }
            val favorites = value.get("favoritesOnly")
            require(favorites is Boolean) { "备份中的收藏偏好无效" }
            return BackupPreferences(category, favorites)
        }
    }
}

internal data class BackupInfo(val id: String, val createdAt: Long, val device: String, val appVersion: String,
    val profiles: Int, val scripts: Int, val stores: Int, val bytes: Long, val options: BackupOptions = BackupOptions.ALL)

internal data class OpenedBackup(val directory: File, val config: AppConfig, val preferences: BackupPreferences, val info: BackupInfo)

/** Portable, bounded ZIP format. This module never writes into live application data. */
internal object BackupArchive {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    const val MAX_STORE_BYTES = 10L * 1024 * 1024
    const val MAX_STORES = 4096
    private const val MAX_SMALL_BYTES = 1024L * 1024
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private val storePath = Regex("userscript-values/[a-f0-9]{64}\\.json")
    private data class Entry(val path: String, val size: Long, val sha256: String)

    fun write(payload: File, destination: File, device: String, appVersion: String,
        options: BackupOptions = BackupOptions.ALL,
        validateStore: (File) -> Unit): BackupInfo {
        val config = ConfigCodec.decode(readText(File(payload, "config.json"), MAX_SMALL_BYTES))
        if (options.preferences) BackupPreferences.parse(readText(File(payload, "preferences.json"), MAX_SMALL_BYTES))
        val stores = if (options.scriptData) File(payload, "userscript-values").listFiles().orEmpty().sortedBy { it.name } else emptyList()
        require(stores.size <= MAX_STORES) { "脚本存储文件过多" }
        stores.forEach { require(storePath.matches("userscript-values/${it.name}")) { "脚本存储文件名无效" }; validateStore(it) }
        val files = listOf(File(payload, "config.json")) + (if (options.preferences) listOf(File(payload, "preferences.json")) else emptyList()) + stores
        var total = 0L
        val entries = files.map { file ->
            val path = file.relativeTo(payload).invariantSeparatorsPath
            val limit = if (storePath.matches(path)) MAX_STORE_BYTES else MAX_SMALL_BYTES
            val (size, hash) = digest(file, limit)
            total += size
            require(total <= MAX_TOTAL_BYTES) { "备份数据超过 1 GiB，请先清理无用脚本数据" }
            Entry(path, size, hash)
        }
        val info = BackupInfo(UUID.randomUUID().toString(), System.currentTimeMillis(), device.take(160), appVersion.take(80),
            config.profiles.size, config.scriptLibrary.size, stores.size, total, options)
        val manifest = JSONObject().put("format", "lightboat-backup").put("version", 2).put("components", options.components())
            .put("id", info.id).put("createdAt", info.createdAt).put("device", info.device).put("appVersion", info.appVersion)
            .put("files", JSONArray().apply { entries.forEach {
                put(JSONObject().put("path", it.path).put("size", it.size).put("sha256", it.sha256))
            } }).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "备份目录过大" }
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                entries.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.path))
                    File(payload, entry.path).inputStream().use {
                        require(copyBounded(it, zip, entry.size) == entry.size) { "生成备份时文件发生变化，请重试" }
                    }
                    zip.closeEntry()
                    require(destination.length() <= MAX_ARCHIVE_BYTES) { "压缩备份不能超过 512 MiB" }
                }
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
            }
            require(destination.length() <= MAX_ARCHIVE_BYTES) { "压缩备份不能超过 512 MiB" }
            return info
        } catch (error: Exception) { destination.delete(); throw error }
    }

    fun open(archive: File, destination: File, validateStore: (File) -> Unit): OpenedBackup {
        require(archive.length() in 1..MAX_ARCHIVE_BYTES) { "备份文件为空或超过 512 MiB" }
        require(!destination.exists()) { "备份解包目录已存在" }
        check(destination.mkdirs()) { "无法创建备份校验目录" }
        try {
            val actual = linkedMapOf<String, Entry>()
            var manifest: JSONObject? = null
            var total = 0L
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                val names = mutableSetOf<String>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    require(names.add(name) && names.size <= MAX_STORES + 3) { "备份包含重复文件或文件数量过多" }
                    require(!entry.isDirectory && (name in setOf("config.json", "preferences.json", "manifest.json") || storePath.matches(name))) {
                        "备份包含不支持的文件路径"
                    }
                    val limit = when (name) { "manifest.json" -> MAX_MANIFEST_BYTES; "config.json", "preferences.json" -> MAX_SMALL_BYTES; else -> MAX_STORE_BYTES }
                    require(entry.size in 0..limit) { "备份中的单个文件超过上限" }
                    val file = File(destination, name)
                    file.parentFile?.mkdirs()
                    val hash = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    var size = 0L
                    FileOutputStream(file).use { output ->
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count; total += count
                                require(size <= limit && total <= MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) { "备份解压数据超过上限" }
                                hash.update(buffer, 0, count); crc.update(buffer, 0, count); output.write(buffer, 0, count)
                            }
                        }
                        output.fd.sync()
                    }
                    require(size == entry.size && crc.value == entry.crc) { "备份文件损坏或不完整" }
                    if (name == "manifest.json") manifest = JSONObject(readText(file, MAX_MANIFEST_BYTES))
                    else actual[name] = Entry(name, size, hex(hash.digest()))
                }
            }
            val index = manifest ?: error("缺少备份清单")
            require(index.getString("format") == "lightboat-backup" && index.getInt("version") in 1..2) { "不支持此备份格式或版本" }
            val options = if (index.getInt("version") == 1) BackupOptions.ALL else BackupOptions.parse(index.getJSONArray("components"))
            val expected = index.getJSONArray("files")
            require(expected.length() == actual.size) { "备份文件清单不完整" }
            val seen = mutableSetOf<String>()
            for (i in 0 until expected.length()) {
                val entry = expected.getJSONObject(i)
                val path = entry.getString("path")
                val found = actual[path]
                require(seen.add(path) && found != null && entry.getLong("size") == found.size && entry.getString("sha256") == found.sha256) {
                    "备份文件校验失败"
                }
            }
            require(actual.containsKey("config.json")) { "备份缺少配置和脚本库" }
            require(actual.containsKey("preferences.json") == options.preferences) { "备份偏好与组件清单不一致" }
            val config = ConfigCodec.decode(readText(File(destination, "config.json"), MAX_SMALL_BYTES))
            val prefs = if (options.preferences) BackupPreferences.parse(readText(File(destination, "preferences.json"), MAX_SMALL_BYTES)) else BackupPreferences(null, false)
            val stores = actual.keys.filter { storePath.matches(it) }
            require(options.scriptData || stores.isEmpty()) { "备份脚本数据与组件清单不一致" }
            stores.forEach { validateStore(File(destination, it)) }
            val id = index.getString("id")
            val createdAt = index.getLong("createdAt")
            val device = index.getString("device")
            val version = index.getString("appVersion")
            require(id.length in 1..100 && createdAt > 0 && device.length <= 160 && version.length <= 80) { "备份信息无效" }
            File(destination, "userscript-values").mkdirs()
            return OpenedBackup(destination, config, prefs, BackupInfo(id, createdAt, device, version,
                config.profiles.size, config.scriptLibrary.size, stores.size, actual.values.sumOf { it.size }, options))
        } catch (error: Exception) { destination.deleteRecursively(); throw error }
    }

    fun readText(file: File, limit: Long): String {
        require(file.isFile && file.length() <= limit) { "备份文本文件缺失或超过上限" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
    }

    fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { "文件超过备份大小上限" }
            output.write(buffer, 0, count)
        }
    }

    private fun digest(file: File, limit: Long): Pair<Long, String> {
        require(file.isFile && file.length() <= limit) { "备份文件缺失或超过上限" }
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; size += count
                require(size <= limit) { "备份文件超过上限" }; hash.update(buffer, 0, count) }
        }
        return size to hex(hash.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

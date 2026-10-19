package com.lightboat.app

import com.lightboat.app.backup.BackupArchive
import com.lightboat.app.backup.BackupRepository
import com.lightboat.app.backup.OpenedBackup
import okhttp3.Call
import java.io.File
import java.io.InputStream
import java.util.UUID

internal data class BackupImportData(val config: AppConfig, val archive: OpenedBackup? = null)

/** Detects content rather than trusting a filename, MIME type or URL suffix. */
internal object BackupImportReader {
    fun fromUrl(call: Call, defaultUserAgent: String, workspace: File, repository: BackupRepository): BackupImportData =
        call.execute().use { response ->
            check(response.isSuccessful) { "读取备份失败（HTTP ${response.code}）" }
            val body = response.body ?: error("服务器未返回备份内容")
            require(body.contentLength() <= BackupArchive.MAX_ARCHIVE_BYTES) { "备份文件超过 512 MiB" }
            body.byteStream().use { fromStream(it, defaultUserAgent, workspace, repository) }
        }

    fun fromStream(input: InputStream, defaultUserAgent: String, workspace: File, repository: BackupRepository): BackupImportData {
        val stream = input.buffered()
        stream.mark(4)
        val first = stream.read(); val second = stream.read()
        stream.reset()
        if (first != 'P'.code || second != 'K'.code) return BackupImportData(ConfigImportReader.fromStream(stream, defaultUserAgent))
        check(workspace.exists() || workspace.mkdirs()) { "无法创建备份临时目录" }
        val archive = File(workspace, "incoming-${UUID.randomUUID()}.zip")
        try {
            archive.outputStream().use { BackupArchive.copyBounded(stream, it, BackupArchive.MAX_ARCHIVE_BYTES) }
            val opened = repository.open(archive, File(workspace, "validated-${UUID.randomUUID()}"))
            return BackupImportData(opened.config, opened)
        } finally { archive.delete() }
    }
}

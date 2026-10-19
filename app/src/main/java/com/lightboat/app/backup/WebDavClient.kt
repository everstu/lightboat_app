package com.lightboat.app.backup

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import com.lightboat.app.HttpClientCleanup
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class WebDavSettings(val directory: String, val username: String, val password: String) {
    val base: HttpUrl get() = directory.toHttpUrl()
    val backupDirectory: HttpUrl get() = base.newBuilder().addPathSegment("lightboat").addPathSegment("").build()
    val backupUrl: HttpUrl get() = backupDirectory.newBuilder().addPathSegment("lightboat-backup.zip").build()
    companion object {
        fun normalized(directory: String, username: String, password: String): WebDavSettings {
            val url = directory.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "请填写不带账号、查询参数和片段的 WebDAV 文件夹地址"
            }
            require(username.length <= 1024 && password.length <= 4096 && username.none { it == '\r' || it == '\n' || it == ':' }) { "WebDAV 账号或密码格式无效" }
            val folder = if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
            return WebDavSettings(folder.toString(), username, password)
        }
    }
}

internal data class RemoteBackup(val exists: Boolean, val etag: String?, val modified: String?, val bytes: Long?)
internal class WebDavConflict : Exception("远端备份已发生变化，请重新检查后再选择上传或恢复。")

/** No browser cookies, profile UA, redirects or credentials in backups/logs. */
internal class WebDavClient(private val settings: WebDavSettings,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build(),
    private val maximumBytes: Long = BackupArchive.MAX_ARCHIVE_BYTES) : AutoCloseable {
    private val calls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    @Volatile private var closed = false

    fun testConnection(): RemoteBackup {
        require(directoryExists(settings.base)) { "WebDAV 父目录不存在，请填写已有文件夹地址" }
        return inspect()
    }

    private fun directoryExists(url: HttpUrl): Boolean {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        return execute(request(url).header("Depth", "0").method("PROPFIND", body).build()) { response ->
            if (response.code == 404) return@execute false
            checkStatus(response, setOf(207))
            val text = response.body?.byteStream()?.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                BackupArchive.copyBounded(input, bytes, 128 * 1024); bytes.toString("UTF-8")
            }.orEmpty()
            require(Regex("<(?:[A-Za-z_][\\w.-]*:)?collection(?:\\s|/|>)").containsMatchIn(text)) {
                "该地址不是可访问的 WebDAV 文件夹，请检查服务器地址和账号权限"
            }
            true
        }
    }

    private fun ensureBackupDirectory() {
        if (directoryExists(settings.backupDirectory)) return
        val created = execute(request(settings.backupDirectory).method("MKCOL", null).build()) { response ->
            // Another device may create this same folder between PROPFIND and MKCOL.
            if (response.code == 405) false else {
                checkStatus(response, setOf(201)); true
            }
        }
        if (!created) require(directoryExists(settings.backupDirectory)) { "无法创建或访问备份子目录" }
    }

    fun inspect(): RemoteBackup = execute(request(settings.backupUrl).head().build()) { response ->
        if (response.code == 404) RemoteBackup(false, null, null, null) else {
            checkStatus(response, setOf(200, 204)); version(response)
        }
    }

    fun upload(file: File, expected: RemoteBackup): RemoteBackup {
        require(file.isFile && file.length() in 1..maximumBytes) { "本地备份文件无效或过大" }
        val builder = request(settings.backupUrl).put(file.asRequestBody("application/zip".toMediaType()))
        if (expected.exists) {
            val tag = expected.etag
            require(tag != null && tag.startsWith('"') && tag.endsWith('"') && !tag.startsWith("W/")) {
                "服务器未提供可用于版本校验的 ETag，无法安全覆盖已有备份；请使用空的备份目录。"
            }
            builder.header("If-Match", tag)
        } else builder.header("If-None-Match", "*")
        ensureBackupDirectory()
        return execute(builder.build()) { response ->
            checkStatus(response, setOf(200, 201, 204))
            version(response)
        }
    }

    fun download(destination: File): RemoteBackup {
        try {
            return execute(request(settings.backupUrl).get().build()) { response ->
                checkStatus(response, setOf(200))
                val body = response.body ?: error("服务器未返回备份内容")
                require(body.contentLength() <= maximumBytes) { "远端备份超过大小上限" }
                FileOutputStream(destination).use { output ->
                    body.byteStream().use { BackupArchive.copyBounded(it, output, maximumBytes) }
                    output.fd.sync()
                }
                require(destination.length() > 0) { "远端备份为空" }
                version(response)
            }
        } catch (error: Exception) { destination.delete(); throw error }
    }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).header("Cache-Control", "no-cache").apply {
        if (settings.username.isNotEmpty() || settings.password.isNotEmpty())
            header("Authorization", Credentials.basic(settings.username, settings.password, Charsets.UTF_8))
    }
    private fun version(response: Response) = RemoteBackup(true, response.header("ETag"), response.header("Last-Modified"),
        response.header("Content-Length")?.toLongOrNull())
    private fun checkStatus(response: Response, allowed: Set<Int>) {
        if (response.code == 412) throw WebDavConflict()
        if (response.code in allowed) return
        error(when (response.code) {
            401 -> "WebDAV 账号或密码不正确（HTTP 401）"
            403 -> "WebDAV 没有访问或写入权限（HTTP 403）"
            404 -> "未找到远端备份或目录（HTTP 404）"
            409 -> "WebDAV 目录不存在或存在冲突（HTTP 409），请填写已有文件夹地址"
            423 -> "远端文件已被锁定，请稍后重试（HTTP 423）"
            301, 302, 303, 307, 308 -> "服务器发生重定向，请填写最终的 WebDAV 文件夹地址"
            507 -> "WebDAV 存储空间不足（HTTP 507）"
            else -> "WebDAV 请求失败（HTTP ${response.code}）"
        })
    }
    private fun <T> execute(request: Request, action: (Response) -> T): T {
        check(!closed) { "WebDAV 操作已取消" }
        val call = client.newCall(request); calls.add(call)
        try { if (closed) call.cancel(); return call.execute().use(action) }
        finally { calls.remove(call) }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        HttpClientCleanup.close(client, calls)
    }
}

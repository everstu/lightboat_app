package com.lightboat.app.update

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class UpdateFailure { NO_RELEASE, RATE_LIMIT, NETWORK, INVALID_RELEASE, NO_APK, AMBIGUOUS_APK, DOWNLOAD, INVALID_APK }
internal class UpdateException(val reason: UpdateFailure) : IOException(reason.name)

/** Numeric stable release tags, compared by components rather than lexicographically. */
internal data class ReleaseVersion(val name: String, private val parts: List<Long>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val comparison = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(tag: String): ReleaseVersion? {
            val name = tag.removePrefix("v").removePrefix("V")
            if (!name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))) return null
            val parts = name.split('.').map { it.toLongOrNull() ?: return null }
            return ReleaseVersion(name, parts)
        }
    }
}

internal data class ReleaseApk(val name: String, val url: HttpUrl, val size: Long, val sha256: String?)
internal data class AppRelease(val tag: String, val version: ReleaseVersion, val notes: String, val apk: ReleaseApk?)

/** Uses its own unauthenticated session; never inherits webpage cookies or profile UA. */
internal class GitHubUpdateClient(
    private val endpoint: HttpUrl = "https://api.github.com/repos/$REPOSITORY/releases/latest".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
        .followSslRedirects(false).build(),
) : AutoCloseable {
    @Volatile private var active: Call? = null
    @Volatile private var closed = false

    fun latest(): AppRelease {
        val request = Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Lightboat-Android-Updater")
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()
        return execute(request) { response ->
            when (response.code) {
                404 -> throw UpdateException(UpdateFailure.NO_RELEASE)
                403, 429 -> throw UpdateException(UpdateFailure.RATE_LIMIT)
            }
            if (!response.isSuccessful) throw UpdateException(UpdateFailure.NETWORK)
            val body = response.body ?: throw UpdateException(UpdateFailure.INVALID_RELEASE)
            if (body.contentLength() > MAX_RELEASE_BYTES) throw UpdateException(UpdateFailure.INVALID_RELEASE)
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RELEASE_BYTES) throw UpdateException(UpdateFailure.INVALID_RELEASE)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            parseRelease(bytes.toString(Charsets.UTF_8))
        }
    }

    fun download(apk: ReleaseApk, destination: File, progress: (Long, Long) -> Unit) {
        try {
            execute(Request.Builder().url(apk.url).header("User-Agent", "Lightboat-Android-Updater").build()) { response ->
                if (response.code != 200) throw UpdateException(UpdateFailure.DOWNLOAD)
                val body = response.body ?: throw UpdateException(UpdateFailure.DOWNLOAD)
                if (apk.size !in 1..MAX_APK_BYTES || body.contentLength() > MAX_APK_BYTES ||
                    (body.contentLength() >= 0 && body.contentLength() != apk.size)) throw UpdateException(UpdateFailure.DOWNLOAD)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (closed) throw IOException("Update cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > apk.size) throw UpdateException(UpdateFailure.DOWNLOAD)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            progress(total, apk.size)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != apk.size || (apk.sha256 != null && !actual.equals(apk.sha256, ignoreCase = true))) {
                    throw UpdateException(UpdateFailure.DOWNLOAD)
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T {
        val call = client.newCall(request)
        active = call
        try {
            if (closed) throw IOException("Update cancelled")
            return call.execute().use(read)
        } finally { active = null }
    }

    override fun close() {
        closed = true
        com.lightboat.app.HttpClientCleanup.close(client, listOfNotNull(active))
    }

    companion object {
        const val REPOSITORY = "everstu/lightboat_app"
        const val MAX_APK_BYTES = 128L * 1024 * 1024
        private const val MAX_RELEASE_BYTES = 1024 * 1024

        fun parseRelease(text: String): AppRelease {
            try {
                val json = JSONObject(text)
                if (json.getBoolean("draft") || json.getBoolean("prerelease")) throw UpdateException(UpdateFailure.INVALID_RELEASE)
                val tag = json.getString("tag_name")
                val version = ReleaseVersion.parse(tag) ?: throw UpdateException(UpdateFailure.INVALID_RELEASE)
                val assets = json.getJSONArray("assets")
                val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
                val expectedName = "lightboat-$tag-debug.apk"
                val matches = apks.filter { it.getString("name") == expectedName }
                val selected = when {
                    matches.size == 1 -> matches.single()
                    apks.isEmpty() -> null
                    apks.size == 1 -> apks.single()
                    else -> throw UpdateException(UpdateFailure.AMBIGUOUS_APK)
                }
                val apk = selected?.let {
                    val url = it.getString("browser_download_url").toHttpUrl()
                    // The API supplies a public GitHub release attachment; no arbitrary mirror or credentials.
                    if (!url.isHttps || url.host != "github.com" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                        !url.encodedPath.startsWith("/$REPOSITORY/releases/download/")) throw UpdateException(UpdateFailure.INVALID_RELEASE)
                    val size = it.getLong("size")
                    if (size !in 1..MAX_APK_BYTES) throw UpdateException(UpdateFailure.INVALID_RELEASE)
                    val digest = it.optString("digest").takeIf { value -> value.isNotEmpty() && value != "null" }?.let { value ->
                        if (!value.matches(Regex("sha256:[0-9a-fA-F]{64}"))) throw UpdateException(UpdateFailure.INVALID_RELEASE)
                        value.substringAfter(':')
                    }
                    ReleaseApk(it.getString("name"), url, size, digest)
                }
                return AppRelease(tag, version, json.optString("body").takeUnless { it == "null" }.orEmpty(), apk)
            } catch (error: UpdateException) { throw error }
            catch (_: Exception) { throw UpdateException(UpdateFailure.INVALID_RELEASE) }
        }
    }
}

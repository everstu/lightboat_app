package com.lightboat.app

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One process-scoped diagnostic log. It is cleared once when a new app process
 * starts and then appended by the WebView, script and media boundaries.
 * Sensitive header values and URL query values are intentionally omitted.
 */
object DebugLog {
    private const val MAX_BYTES = 1_048_576L
    private const val KEEP_BYTES = 524_288
    private val lock = Any()
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var initialized = false
    private var generation = 0L

    internal data class Cursor(val generation: Long, val offset: Long)
    internal data class Update(val cursor: Cursor, val reset: Boolean, val text: String)

    fun initialize(context: Context) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences("debug-log", Context.MODE_PRIVATE)
        val previousCrash: String?
        synchronized(lock) {
            if (initialized) return
            file = File(app.filesDir, "debug.log")
            runCatching { file!!.writeText("", Charsets.UTF_8) }
            initialized = true
            previousCrash = preferences.getString("previous-crash", null)
            preferences.edit().remove("previous-crash").apply()
        }
        event("app", "process started")
        previousCrash?.let { event("previous-crash", it) }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().take(16_000)
            event("crash", "thread=${thread.name} $trace")
            preferences.edit().putString("previous-crash", "thread=${thread.name} $trace").commit()
            if (previousHandler != null) previousHandler.uncaughtException(thread, error) else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun event(area: String, message: String) {
        synchronized(lock) {
            val target = file ?: return
            val clean = message.replace(Regex("https?://[^\\s\\\"']+", RegexOption.IGNORE_CASE)) {
                    safeUrl(it.value)
                }
                .replace(Regex("(?i)\\b(authorization|cookie)\\s*[:=]\\s*[^\\s,;]+")) {
                    "${it.groupValues[1]}=<redacted>"
                }
                .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer <redacted>")
                .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
                .replace('\n', ' ').replace('\r', ' ').take(4000)
            val line = "${clock.format(Date())} [$area] $clean\n"
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val current = target.readBytes()
                    val tail = current.copyOfRange((current.size - KEEP_BYTES).coerceAtLeast(0), current.size)
                    val firstLineEnd = tail.indexOf('\n'.code.toByte())
                    val completeLines = if (firstLineEnd >= 0) tail.copyOfRange(firstLineEnd + 1, tail.size) else tail
                    target.writeBytes("--- log trimmed ---\n".toByteArray(Charsets.UTF_8) + completeLines)
                    generation++
                }
                target.appendText(line, Charsets.UTF_8)
            }
        }
    }

    internal fun readAfter(cursor: Cursor?): Update = synchronized(lock) {
        val target = file?.takeIf { it.exists() }
        val size = target?.length() ?: 0L
        val reset = cursor == null || cursor.generation != generation || cursor.offset > size
        val offset = if (reset) 0L else cursor!!.offset
        val text = if (target != null && size > offset) {
            RandomAccessFile(target, "r").use { input ->
                input.seek(offset)
                val bytes = ByteArray((size - offset).toInt())
                input.readFully(bytes)
                bytes.toString(Charsets.UTF_8)
            }
        } else ""
        Update(Cursor(generation, size), reset, text)
    }

    internal fun clear(): Unit = synchronized(lock) {
        file?.writeText("", Charsets.UTF_8)
        generation++
        Unit
    }

    fun safeUrl(value: String?): String {
        if (value.isNullOrBlank()) return "<empty>"
        // Previously sanitized addresses must not acquire duplicate redaction markers.
        val uri = runCatching { URI(value.replace("<redacted>", "redacted")) }.getOrNull() ?: return "<invalid-url>"
        val scheme = uri.scheme?.lowercase() ?: return "<invalid-url>"
        val host = uri.host?.lowercase() ?: return "$scheme://<no-host>"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty().ifBlank { "/" }.take(512)
        val queryKeys = uri.rawQuery.orEmpty().split('&')
            .mapNotNull { it.substringBefore('=').takeIf(String::isNotBlank) }
            .distinct().take(20)
        val query = if (queryKeys.isEmpty()) "" else "?${queryKeys.joinToString("&") { "$it=<redacted>" }}"
        return "$scheme://$host$port$path$query"
    }

    fun shouldTraceUrl(value: String?): Boolean {
        val uri = runCatching { URI(value.orEmpty()) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        return path.contains("/api/") || path.contains("meta") || isMediaUrl(value) ||
            uri.rawQuery.orEmpty().contains("ep=", ignoreCase = true)
    }

    fun isMediaUrl(value: String?): Boolean {
        val uri = runCatching { URI(value.orEmpty()) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4") ||
            path.endsWith(".m4s") || path.endsWith(".ts") ||
            path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".key") ||
            path.endsWith(".webm") || path.endsWith(".ogg") || path.endsWith(".ogv")
    }
}

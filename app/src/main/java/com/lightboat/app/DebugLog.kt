package com.lightboat.app

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
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
            val clean = message.replace(Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)) {
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
                    target.writeBytes("--- log trimmed ---\n".toByteArray(Charsets.UTF_8) + tail)
                }
                target.appendText(line, Charsets.UTF_8)
            }
        }
    }

    fun read(): String = synchronized(lock) {
        file?.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
    }

    fun safeUrl(value: String?): String {
        if (value.isNullOrBlank()) return "<empty>"
        val uri = runCatching { URI(value) }.getOrNull() ?: return "<invalid-url>"
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

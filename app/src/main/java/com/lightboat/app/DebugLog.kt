package com.lightboat.app

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One process-scoped diagnostic log. It is cleared once when a new app process
 * starts and then appended by the WebView, script and media boundaries.
 * Sensitive header values and URL query values are intentionally omitted.
 */
object DebugLog {
    internal const val MAX_ENTRIES = 2000
    private val lock = Any()
    private val buffer = DebugLogBuffer(MAX_ENTRIES)
    private val writer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "debug-log-writer").apply { isDaemon = true } }
    private var writeScheduled = false
    private val urlPattern = Regex("https?://[^\\s\\\"']+", RegexOption.IGNORE_CASE)
    private val secretPattern = Regex("(?i)\\b(authorization|cookie)\\s*[:=]\\s*[^\\s,;]+")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val controlPattern = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var initialized = false
    private var generation = 0L

    internal data class Cursor(val generation: Long, val offset: Long)
    internal data class Update(val cursor: Cursor, val reset: Boolean, val lines: List<String>) {
        val text get() = lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")
    }

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
            flush()
            preferences.edit().putString("previous-crash", "thread=${thread.name} $trace").commit()
            if (previousHandler != null) previousHandler.uncaughtException(thread, error) else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun event(area: String, message: String) {
        synchronized(lock) {
            if (file == null) return
            val clean = message.replace(urlPattern) {
                    safeUrl(it.value)
                }
                .replace(secretPattern) {
                    "${it.groupValues[1]}=<redacted>"
                }
                .replace(bearerPattern, "Bearer <redacted>")
                .replace(controlPattern, " ")
                .replace('\n', ' ').replace('\r', ' ').take(4000)
            buffer.append("${clock.format(Date())} [$area] $clean")
            if (!writeScheduled) {
                writeScheduled = true
                writer.schedule({ synchronized(lock) { writeScheduled = false; writeFile() } }, 250, TimeUnit.MILLISECONDS)
            }
        }
    }

    internal fun readAfter(cursor: Cursor?): Update = synchronized(lock) {
        val update = buffer.read(cursor?.takeIf { it.generation == generation }?.offset)
        Update(Cursor(generation, update.sequence), update.reset, update.lines)
    }

    internal fun clear(): Unit = synchronized(lock) {
        buffer.clear()
        generation++
        writeFile()
        Unit
    }

    internal fun flush() = synchronized(lock) { writeFile() }

    private fun writeFile() {
        val atomic = file?.let(::AtomicFile) ?: return
        runCatching {
            val output = atomic.startWrite()
            try {
                val text = output.bufferedWriter(Charsets.UTF_8)
                buffer.read(null).lines.forEach { text.append(it).append('\n') }
                text.flush()
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); throw error }
        }
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

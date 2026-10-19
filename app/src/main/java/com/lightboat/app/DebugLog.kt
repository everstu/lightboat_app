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
    private const val WRITE_INTERVAL_MS = 2000L
    private val lock = Any()
    private val diskLock = Any()
    private val buffer = DebugLogBuffer(MAX_ENTRIES)
    private val writer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "debug-log-writer").apply { isDaemon = true } }
    private var writeScheduled = false
    private val urlPattern = Regex("https?://[^\\s\\\"']+", RegexOption.IGNORE_CASE)
    // Header values may contain spaces, commas or semicolons; redact the full line.
    private val secretPattern = Regex("(?i)\\b((?:proxy-)?authorization|(?:set-)?cookie)[\"']?\\s*[:=][^\\r\\n]*")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val controlPattern = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var initialized = false
    private var generation = 0L
    private var revision = 0L
    private var persistedRevision = -1L
    private data class Snapshot(val file: File, val revision: Long, val lines: List<String>)

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
            revision++
            initialized = true
            previousCrash = preferences.getString("previous-crash", null)
            preferences.edit().remove("previous-crash").apply()
        }
        flush()
        event("app", "process started")
        previousCrash?.let { event("previous-crash", it) }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = sanitize(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()).take(16_000)
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
            val clean = sanitize(message).take(4000)
            buffer.append("${clock.format(Date())} [$area] $clean")
            revision++
            if (!writeScheduled) {
                writeScheduled = true
                writer.schedule({
                    synchronized(lock) { writeScheduled = false }
                    writeFile()
                }, WRITE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    internal fun sanitize(message: String): String = message.replace(urlPattern) { safeUrl(it.value) }
        .replace(secretPattern) { "${it.groupValues[1]}=<redacted>" }
        .replace(bearerPattern, "Bearer <redacted>")
        .replace(controlPattern, " ")
        .replace('\n', ' ').replace('\r', ' ')

    internal fun readAfter(cursor: Cursor?): Update = synchronized(lock) {
        val update = buffer.read(cursor?.takeIf { it.generation == generation }?.offset)
        Update(Cursor(generation, update.sequence), update.reset, update.lines)
    }

    internal fun clear() {
        synchronized(lock) {
            buffer.clear()
            generation++
            revision++
        }
        writeFile()
    }

    internal fun flush() = writeFile()

    private fun writeFile() {
        // Serialize disk writes separately. Logging and live UI reads never wait for fsync.
        synchronized(diskLock) {
            val snapshot = synchronized(lock) {
                val target = file ?: return
                if (revision == persistedRevision) return
                Snapshot(target, revision, buffer.read(null).lines)
            }
            val atomic = AtomicFile(snapshot.file)
            val saved = runCatching {
                val output = atomic.startWrite()
                try {
                    val text = output.bufferedWriter(Charsets.UTF_8)
                    snapshot.lines.forEach { text.append(it).append('\n') }
                    text.flush()
                    atomic.finishWrite(output)
                } catch (error: Exception) { atomic.failWrite(output); throw error }
            }.isSuccess
            if (saved) synchronized(lock) { persistedRevision = snapshot.revision }
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

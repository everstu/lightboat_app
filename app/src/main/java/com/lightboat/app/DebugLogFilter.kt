package com.lightboat.app

internal enum class DebugLogLevel { INFO, WARNING, ERROR }

internal class DebugLogEntry(val raw: String) {
    private val match = LINE.matchEntire(raw)
    val time = match?.groupValues?.get(1).orEmpty()
    val source = match?.groupValues?.get(2) ?: "app"
    val message = match?.groupValues?.get(3) ?: raw
    // Existing logs are plain text. Infer a level without treating empty error=
    // fields on successful requests as failures.
    val level = when {
        source.contains("error", ignoreCase = true) || source.contains("crash", ignoreCase = true) ||
            ERROR.containsMatchIn(message) -> DebugLogLevel.ERROR
        WARNING.containsMatchIn(message) -> DebugLogLevel.WARNING
        else -> DebugLogLevel.INFO
    }

    companion object {
        private val LINE = Regex("^\\d{4}-\\d{2}-\\d{2} (\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[([^]]+)] (.*)$")
        private val ERROR = Regex("(^error(?:\\s|:|$)|\\bevent=error\\b|\\bok=false\\b|\\b(?:failed|failure|exception)\\b(?![=:]false)|\\bstatus=[45]\\d{2}\\b)", RegexOption.IGNORE_CASE)
        private val WARNING = Regex("(^warn(?:ing)?(?:\\s|:|$)|\\btimeout\\b|\\btimed out\\b|\\bevent=abort\\b|超时)", RegexOption.IGNORE_CASE)
    }
}

internal data class DebugLogFilter(val query: String = "", val source: String? = null, val level: DebugLogLevel? = null) {
    val active get() = query.isNotBlank() || source != null || level != null

    fun matches(entry: DebugLogEntry): Boolean =
        (source == null || source == entry.source) && (level == null || level == entry.level) &&
            (query.isBlank() || entry.raw.contains(query.trim(), ignoreCase = true))
}

package com.lightboat.app.backup

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** HTTP Last-Modified and ISO timestamps describe instants, not local wall times. */
internal object WebDavTime {
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'GMT'xxx", Locale.ROOT)

    fun parse(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            try { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            catch (_: DateTimeParseException) { null }
        }
    }

    fun format(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        parse(value)?.let { display.withZone(zone).format(it) }
}

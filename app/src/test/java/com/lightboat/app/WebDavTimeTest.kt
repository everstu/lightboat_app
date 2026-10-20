package com.lightboat.app

import com.lightboat.app.backup.WebDavTime
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WebDavTimeTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun httpGmtDatesAreDisplayedInTheSelectedLocalZone() {
        val value = "Mon, 19 Oct 2026 09:38:38 GMT"
        assertEquals(Instant.parse("2026-10-19T09:38:38Z"), WebDavTime.parse(value))
        assertEquals("2026-10-19 17:38:38 GMT+08:00", WebDavTime.format(value, shanghai))
        assertEquals("2026-10-19 09:38:38 GMT+00:00", WebDavTime.format(value, ZoneId.of("UTC")))
    }

    @Test fun isoOffsetDatesRetainNanosecondsAndNeverApplyTheOffsetTwice() {
        val value = "2026-10-19T16:31:31.978135517+08:00"
        assertEquals(Instant.parse("2026-10-19T08:31:31.978135517Z"), WebDavTime.parse(value))
        assertEquals("2026-10-19 16:31:31 GMT+08:00", WebDavTime.format(value, shanghai))
        assertEquals("2026-10-19 08:31:31 GMT+00:00", WebDavTime.format(value, ZoneId.of("UTC")))
    }

    @Test fun zAndExplicitOffsetsIdentifyTheSameInstantAndHandleDayBoundaries() {
        val value = "2026-10-19T08:31:31.978135517Z"
        assertEquals(WebDavTime.parse(value), WebDavTime.parse("2026-10-19T04:31:31.978135517-04:00"))
        assertEquals(WebDavTime.parse(value), WebDavTime.parse("2026-10-19T14:01:31.978135517+05:30"))
        assertEquals("2026-10-19 16:31:31 GMT+08:00", WebDavTime.format(value, shanghai))
        assertEquals("2026-10-20 07:30:00 GMT+08:00", WebDavTime.format("2026-10-19T23:30:00Z", shanghai))
    }

    @Test fun localZoneOffsetsFollowDaylightSavingAtTheActualInstant() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        assertEquals("2026-11-01 01:30:00 GMT-07:00", WebDavTime.format("2026-11-01T08:30:00Z", losAngeles))
        assertEquals("2026-11-01 01:30:00 GMT-08:00", WebDavTime.format("2026-11-01T09:30:00Z", losAngeles))
    }

    @Test fun missingOrInvalidTimesNeverGuessAnUnspecifiedTimeZone() {
        for (value in listOf(null, "", "  ", "not a date", "2026-10-19T16:31:31", "2026-02-30T16:31:31Z", "2026-10-19T16:31:31+25:00")) {
            assertNull("Unexpected parse: $value", WebDavTime.parse(value))
            assertNull("Unexpected display: $value", WebDavTime.format(value, shanghai))
        }
        assertEquals("2026-10-19 16:31:31 GMT+08:00", WebDavTime.format(" 2026-10-19T08:31:31Z ", shanghai))
    }
}

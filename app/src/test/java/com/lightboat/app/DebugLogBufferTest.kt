package com.lightboat.app

import org.junit.Assert.*
import org.junit.Test

class DebugLogBufferTest {
    @Test fun retainsExactlyTheLatest2000AndResetsAnExpiredCursor() {
        val log = DebugLogBuffer()
        log.append("old")
        val cursor = log.read(null).sequence
        repeat(2500) { log.append("line-$it") }
        val result = log.read(cursor)
        assertTrue(result.reset)
        assertEquals((500 until 2500).map { "line-$it" }, result.lines)
        assertTrue(log.read(result.sequence).lines.isEmpty())
        log.append("next")
        assertEquals(listOf("next"), log.read(result.sequence).lines)
        assertEquals(2000, log.read(null).lines.size)
    }

    @Test fun returnsExistingStringsAndClearCanBeFollowedByNewRecords() {
        val log = DebugLogBuffer()
        val line = String(charArrayOf('a', 'b'))
        log.append(line)
        assertSame(line, log.read(null).lines.single())
        log.clear()
        assertTrue(log.read(null).lines.isEmpty())
        log.append("after-clear")
        assertEquals(listOf("after-clear"), log.read(null).lines)
    }
}

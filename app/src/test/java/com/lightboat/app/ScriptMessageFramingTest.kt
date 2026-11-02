package com.lightboat.app

import com.lightboat.app.userscript.ScriptMessageFraming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the same framing that [com.lightboat.app.userscript.ScriptMessageTransport]
 * sends, then parses the joined parts. Node tests that feed hand-written replies
 * cannot catch a broken native header; these vectors can.
 */
class ScriptMessageFramingTest {
    private fun parse(parts: List<String>) = JSONObject(parts.joinToString(""))

    @Test
    fun getValueReplyKeepsPresentAndValueInsideResult() {
        val value = """{"type":"json","value":"stored-rules"}"""
        val parts = ScriptMessageFraming.valueResult("frame-1", "request-1", value)

        assertEquals(
            """{"doc":"frame-1","id":"request-1","result":{"present":true,"value":$value}}""",
            parts.joinToString(""),
        )
        val message = parse(parts)
        assertEquals("frame-1", message.getString("doc"))
        assertEquals("request-1", message.getString("id"))
        assertFalse("present must not leak to the message top level", message.has("present"))
        assertFalse("value must not leak to the message top level", message.has("value"))
        val result = message.getJSONObject("result")
        assertTrue(result.getBoolean("present"))
        assertEquals("stored-rules", result.getJSONObject("value").getString("value"))
    }

    @Test
    fun missingValueReplyKeepsTheResultEnvelopeValid() {
        val parts = ScriptMessageFraming.valueResult("frame-1", "request-1", null)
        val message = parse(parts)

        assertEquals("""{"doc":"frame-1","id":"request-1","result":{"present":false,"value":null}}""", parts.joinToString(""))
        val result = message.getJSONObject("result")
        assertFalse(result.getBoolean("present"))
        assertTrue(result.isNull("value"))
        assertFalse(message.has("value"))
    }

    @Test
    fun headerFieldsAreEscapedWithoutBreakingTheValue() {
        val doc = "frame\"1\\two\nlines"
        val id = "请求-\"1\""
        val value = """{"type":"json","value":{"closing":"}","braces":[{},[]],"quote":"\""}}"""
        val message = parse(ScriptMessageFraming.valueResult(doc, id, value))

        assertEquals(doc, message.getString("doc"))
        assertEquals(id, message.getString("id"))
        val stored = message.getJSONObject("result").getJSONObject("value")
        assertEquals("}", stored.getJSONObject("value").getString("closing"))
        assertEquals("\"", stored.getJSONObject("value").getString("quote"))
        assertEquals(2, stored.getJSONObject("value").getJSONArray("braces").length())
    }

    @Test
    fun valueNotificationsStillPlaceThePayloadAtTheTopLevel() {
        val value = """{"type":"json","value":[1,{"nested":true}]}"""
        val message = parse(ScriptMessageFraming.value("frame-1", "value", "key-\"1\"", value, deleted = false))

        assertEquals("frame-1", message.getString("doc"))
        assertEquals("value", message.getString("event"))
        assertEquals("key-\"1\"", message.getString("key"))
        assertFalse(message.getBoolean("deleted"))
        assertEquals(1, message.getJSONObject("value").getJSONArray("value").getInt(0))
        assertFalse(message.has("result"))
    }

    @Test
    fun deletedValueNotificationUsesJsonNull() {
        val message = parse(ScriptMessageFraming.value("frame-1", "value", "key", null, deleted = true))

        assertTrue(message.getBoolean("deleted"))
        assertTrue(message.isNull("value"))
    }
}

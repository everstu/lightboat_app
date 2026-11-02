package com.lightboat.app

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.JavaScriptReplyProxy
import com.lightboat.app.userscript.ScriptMessageTransport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScriptMessageTransportTest {
    private class RecordingReply : JavaScriptReplyProxy() {
        val packets = CopyOnWriteArrayList<JSONObject>()
        @Volatile lateinit var transport: ScriptMessageTransport

        override fun postMessage(message: String) {
            val packet = JSONObject(message)
            packets += packet
            if (packet.optString("event") == "bridgeChunk") {
                transport.acknowledge(packet.getString("transfer"), packet.getInt("index"))
            }
        }

        override fun postMessage(message: ByteArray) = error("Unexpected binary GM reply")

        fun text(): String = packets.filter { it.optString("event") == "bridgeChunk" }
            .sortedBy { it.getInt("index") }.joinToString("") { it.getString("text") }
    }

    private fun send(value: String?): JSONObject {
        val reply = RecordingReply()
        val transport = ScriptMessageTransport("frame-1", reply, Handler(Looper.getMainLooper()))
        reply.transport = transport
        val done = CountDownLatch(1)
        val worker = Thread {
            try { transport.sendValueResult("request-1", value) } finally { done.countDown() }
        }.apply { start() }
        try {
            assertTrue("GM value reply was not acknowledged", done.await(10, TimeUnit.SECONDS))
            return JSONObject(reply.text())
        } finally {
            transport.close()
            worker.join(1000)
        }
    }

    @Test fun getValueReplyUsesTheRpcResultEnvelope() {
        val message = send("""{"type":"json","value":"stored-rules"}""")
        assertEquals("frame-1", message.getString("doc"))
        assertEquals("request-1", message.getString("id"))
        assertFalse("present/value must not be placed at the message top level", message.has("present"))
        val result = message.getJSONObject("result")
        assertTrue(result.getBoolean("present"))
        assertEquals("stored-rules", result.getJSONObject("value").getString("value"))
    }

    @Test fun missingValueReplyAlsoUsesTheResultEnvelope() {
        val message = send(null)
        val result = message.getJSONObject("result")
        assertFalse(result.getBoolean("present"))
        assertTrue(result.isNull("value"))
    }
}

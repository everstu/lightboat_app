package com.lightboat.app.userscript

import android.os.Handler
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Worker-side sender with one acknowledged chunk in flight per transfer. */
internal class ScriptMessageTransport(private val doc: String, private val reply: JavaScriptReplyProxy, private val main: Handler) : AutoCloseable {
    private data class Pending(val index: Int, val latch: CountDownLatch = CountDownLatch(1), @Volatile var accepted: Boolean = false)
    private val waiting = ConcurrentHashMap<String, Pending>()
    @Volatile private var closed = false

    fun acknowledge(id: String, index: Int) {
        waiting[id]?.takeIf { it.index == index }?.let { it.accepted = true; it.latch.countDown() }
    }

    fun send(message: JSONObject) = sendParts(listOf(message.toString()))

    fun sendValue(event: String, key: String, value: String?, deleted: Boolean = false) {
        sendParts(ScriptMessageFraming.value(doc, event, key, value, deleted))
    }

    /** A GM.getValue reply; [value] is already the encoded JSON value text. */
    fun sendValueResult(id: String, value: String?) {
        // JS RPC resolves message.result, so the payload must be nested there.
        sendParts(ScriptMessageFraming.valueResult(doc, id, value))
    }

    private fun sendParts(parts: List<String>) {
        val total = parts.sumOf { it.length }
        require(total <= ScriptLimits.MAX_BYTES * 6 + 65536) { "GM 消息过大" }
        val transfer = UUID.randomUUID().toString()
        var part = 0; var offset = 0; var sent = 0; var index = 0
        while (sent < total) {
            if (closed || Thread.currentThread().isInterrupted) throw IOException("脚本文档已关闭")
            val text = buildString {
                while (length < ScriptLimits.CHUNK_CHARS && part < parts.size) {
                    val value = parts[part]
                    var count = minOf(ScriptLimits.CHUNK_CHARS - length, value.length - offset)
                    // Never split UTF-16 surrogate pairs across JSON messages.
                    if (count > 0 && offset + count < value.length && value[offset + count - 1].isHighSurrogate()) count--
                    if (count == 0 && offset < value.length) break
                    append(value, offset, offset + count); offset += count
                    if (offset == value.length) { part++; offset = 0 }
                }
            }
            sent += text.length
            val pending = Pending(index)
            waiting[transfer] = pending
            val packet = JSONObject().put("doc", doc).put("event", "bridgeChunk").put("transfer", transfer)
                .put("index", index).put("total", total).put("last", sent == total).put("text", text).toString()
            main.post {
                if (closed || waiting[transfer] !== pending) pending.latch.countDown() else try { reply.postMessage(packet) }
                catch (_: Exception) { pending.latch.countDown() }
            }
            try {
                if (!pending.latch.await(15, TimeUnit.SECONDS) || !pending.accepted) {
                    // Abort only this transfer. The same live document may acknowledge
                    // later requests after a pause; only document disposal closes it.
                    throw IOException("GM 分片传输已取消或超时")
                }
            } finally { waiting.remove(transfer, pending) }
            index++
        }
    }

    override fun close() { closed = true; waiting.values.forEach { it.latch.countDown() }; waiting.clear() }
}

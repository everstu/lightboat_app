package com.lightboat.app.userscript

import android.content.Context
import android.util.AtomicFile
import com.lightboat.app.DebugLog
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

class ScriptValueStore(context: Context, identity: String) {
    private val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val directory = File(context.applicationContext.filesDir, "userscript-values")
    private val file = AtomicFile(File(directory, "$hash.json"))
    private var values: JSONObject? = null

    @Synchronized
    fun snapshot(): JSONObject {
        val current = values ?: load().also { values = it }
        return JSONObject(current.toString())
    }

    private fun load(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject()
        return try {
            val bytes = file.openRead().use {
                if (it.channel.size() > 1024 * 1024) throw IOException("GM storage exceeds 1 MiB")
                it.readBytes()
            }
            JSONObject(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        } catch (error: Exception) {
            // A damaged script store must not prevent the page or script from starting.
            // Keep one bounded recovery copy; never put stored values in debug logs.
            if (file.baseFile.length() <= 1024 * 1024) runCatching {
                file.baseFile.copyTo(File(directory, "$hash.invalid.json"), overwrite = true)
            }
            DebugLog.event("userscript-store", "ignored invalid storage (${error.javaClass.simpleName}); using empty values")
            JSONObject()
        }
    }

    @Synchronized
    fun update(key: String, value: JSONObject?) {
        require(key.length <= 512) { "存储键不能超过 512 字符" }
        val next = snapshot()
        if (value == null) next.remove(key) else next.put(key, value)
        val bytes = next.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024) { "每个脚本的 GM 存储不能超过 1 MiB" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream); values = next }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

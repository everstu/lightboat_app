package com.lightboat.app.userscript

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ScriptValueStore(context: Context, identity: String) {
    private val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val directory = File(context.filesDir, "userscript-values").apply { mkdirs() }
    private val file = AtomicFile(File(directory, "$hash.json"))
    private var values = if (file.baseFile.exists()) {
        file.openRead().bufferedReader().use { JSONObject(it.readText()) }
    } else JSONObject()

    fun snapshot() = JSONObject(values.toString())

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

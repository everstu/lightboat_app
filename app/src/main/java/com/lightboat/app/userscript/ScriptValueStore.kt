package com.lightboat.app.userscript

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import com.lightboat.app.DebugLog
import org.json.JSONObject
import java.io.File
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

class ScriptValueStore(context: Context, identity: String, legacyIdentity: String? = null) {
    private val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val directory = File(context.applicationContext.filesDir, "userscript-values")
    private val file = AtomicFile(File(directory, "$hash.json"))
    private val legacyFile = legacyIdentity?.let { value ->
        val legacyHash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        AtomicFile(File(directory, "$legacyHash.json"))
    }
    // Encoded values share references across snapshots and writes; no deep JSON copies.
    private var values: Map<String, String>? = null

    @Synchronized
    internal fun forEachEncoded(action: (String, String) -> Unit) = current().forEach(action)

    @Synchronized
    internal fun encoded(key: String) = current()[key]

    private fun current(): Map<String, String> = values ?: load().also { values = it }

    private fun load(): Map<String, String> {
        return try {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                val legacy = legacyFile?.takeIf { it.baseFile.exists() || File(it.baseFile.path + ".bak").exists() }
                    ?: return emptyMap()
                // Storage-worker-only, atomic migration. Keep the original for old app backups;
                // future references use the new ID even after removing and reselecting a script.
                legacy.openRead().use { input ->
                    require(input.channel.size() <= ScriptLimits.MAX_BYTES) { "GM storage exceeds 10 MiB" }
                    val output = file.startWrite()
                    try { input.copyTo(LimitedScriptOutput(output)); file.finishWrite(output) }
                    catch (error: Exception) { file.failWrite(output); throw error }
                }
            }
            file.openRead().use { input ->
                require(input.channel.size() <= ScriptLimits.MAX_BYTES) { "GM storage exceeds 10 MiB" }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                JsonReader(java.io.InputStreamReader(input, decoder)).use { reader ->
                    val result = linkedMapOf<String, String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key.length <= 512 && key !in result)
                        result[key] = ScriptValueCodec.read(reader)
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                    result
                }
            }
        } catch (error: Exception) {
            // A damaged script store must not prevent the page or script from starting.
            // Keep one bounded recovery copy; never put stored values in debug logs.
            if (file.baseFile.length() <= ScriptLimits.MAX_BYTES) runCatching {
                file.baseFile.copyTo(File(directory, "$hash.invalid.json"), overwrite = true)
            }
            DebugLog.event("userscript-store", "ignored invalid storage (${error.javaClass.simpleName}); using empty values")
            emptyMap()
        }
    }

    @Synchronized
    fun update(key: String, value: JSONObject?) = updateEncoded(key, value?.toString())

    @Synchronized
    internal fun updateEncoded(key: String, value: String?) {
        require(key.length <= 512) { "存储键不能超过 512 字符" }
        if (value != null) {
            require(value.length <= ScriptLimits.MAX_BYTES) { "GM 存储超过 10 MiB" }
            ScriptValueCodec.validate(value)
        }
        val next = current().toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        val stream = file.startWrite()
        try {
            val writer = LimitedScriptOutput(stream).bufferedWriter(Charsets.UTF_8)
            writer.write("{")
            next.entries.forEachIndexed { index, (name, encoded) ->
                if (index > 0) writer.write(",")
                writer.write(JSONObject.quote(name)); writer.write(":"); writer.write(encoded)
            }
            writer.write("}"); writer.flush()
            file.finishWrite(stream); values = next
        }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

}

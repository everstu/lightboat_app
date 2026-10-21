package com.lightboat.app.userscript

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import com.lightboat.app.DebugLog
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

class ScriptValueStore(
    context: Context,
    identity: String,
    legacyIdentity: String? = null,
    migrationCandidates: List<String> = emptyList(),
) {
    private data class Candidate(val identity: String, val file: AtomicFile)

    private val hash = hashOf(identity)
    private val directory = File(context.applicationContext.filesDir, "userscript-values")
    private val file = AtomicFile(File(directory, "$hash.json"))
    private val candidates = (listOfNotNull(legacyIdentity) + migrationCandidates)
        .filter { it != identity }.distinct().map { Candidate(it, AtomicFile(File(directory, "${hashOf(it)}.json"))) }
    // Encoded values share references across snapshots and writes; no deep JSON copies.
    private var values: Map<String, String>? = null

    @Synchronized
    internal fun forEachEncoded(action: (String, String) -> Unit) = current().forEach(action)

    @Synchronized
    internal fun encoded(key: String) = current()[key]

    /** Drop a large snapshot after the last active host releases the shared store. */
    @Synchronized
    internal fun discardCache() { values = null }

    private fun current(): Map<String, String> = values ?: load().also { values = it }

    private fun load(): Map<String, String> {
        if (!exists(file)) {
            // A canonical .bak is an AtomicFile recovery copy, not a migration source.
            migrate()?.let { return it }
            return emptyMap()
        }
        val canonical = try {
            read(file)
        } catch (error: Exception) {
            // A damaged script store must not prevent the page or script from starting.
            // Keep one bounded recovery copy; never put stored values in debug logs.
            quarantine(error)
            return emptyMap()
        }
        cleanupContained(canonical)
        return canonical
    }

    private fun exists(atomicFile: AtomicFile) =
        atomicFile.baseFile.exists() || File(atomicFile.baseFile.path + ".bak").exists()

    private fun quarantine(error: Exception) {
        if (file.baseFile.length() <= ScriptLimits.MAX_BYTES) runCatching {
            file.baseFile.copyTo(File(directory, "$hash.invalid.json"), overwrite = true)
        }
        DebugLog.event("userscript-store", "ignored invalid storage (${error.javaClass.simpleName}); using empty values")
    }

    /**
     * One-time merge from old profile-scoped identities. The canonical file is
     * only written when neither it nor its AtomicFile backup exist.
     */
    private fun migrate(): Map<String, String>? {
        val sources = candidates.filter { exists(it.file) }
        if (sources.isEmpty()) return null
        val merged = linkedMapOf<String, String>()
        val sourceMaps = mutableListOf<Pair<Candidate, Map<String, String>>>()
        val keptBy = mutableMapOf<String, String>()
        var size = 2L // { }
        var overflow = false
        var conflicts = 0
        for (candidate in sources) {
            val source = try { read(candidate.file) } catch (error: Exception) {
                DebugLog.event("userscript-store",
                    "migration skipped unreadable source ${candidate.identity} (${error.javaClass.simpleName})")
                null
            } ?: continue
            sourceMaps += candidate to source
            for ((key, value) in source) {
                val existing = merged[key]
                if (existing == null) {
                    size += utf8ByteLength(JSONObject.quote(key)) + 1L + utf8ByteLength(value) +
                        if (merged.isEmpty()) 0L else 1L
                    if (size > ScriptLimits.MAX_BYTES) { overflow = true; break }
                    merged[key] = value
                    keptBy[key] = candidate.identity
                } else if (existing != value) {
                    // Candidate order is deliberate: the currently opened profile wins.
                    // Keep the losing source file until the user can inspect it. Never
                    // log a raw GM key: keys can themselves contain account data.
                    conflicts++
                    DebugLog.event("userscript-store",
                        "migration conflict keyHash=${hashOf(key).take(16)} kept=${keptBy[key]} " +
                            "ignored=${candidate.identity} (first candidate wins)")
                }
            }
            if (overflow) break
        }
        if (overflow) {
            DebugLog.event("userscript-store",
                "migration aborted: merged size exceeds 10 MiB; all sources kept")
            throw IllegalStateException("GM 存储迁移后超过 10 MiB，已保留原数据")
        }
        if (merged.isEmpty()) return null
        val encoded = serialize(merged)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        if (bytes.size > ScriptLimits.MAX_BYTES) {
            DebugLog.event("userscript-store",
                "migration aborted: merged ${bytes.size} bytes exceeds 10 MiB; all sources kept")
            throw IllegalStateException("GM 存储迁移后超过 10 MiB，已保留原数据")
        }
        val stream = file.startWrite()
        try {
            stream.write(bytes); stream.flush(); file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw IllegalStateException("GM 存储迁移写入失败，已保留原数据", error)
        }
        values = merged
        // Delete a source only when every key/value it contains is already in the
        // committed canonical file. A conflict loser keeps its unique value.
        sourceMaps.forEach { (candidate, source) ->
            if (source.all { (key, value) -> merged[key] == value }) deleteSource(candidate)
        }
        DebugLog.event("userscript-store",
            "migrated shared GM storage from ${sourceMaps.size} candidate(s), keys=${merged.size}, conflicts=$conflicts")
        return merged
    }

    /** When the canonical file already exists, old fully-contained copies are safe to remove. */
    private fun cleanupContained(canonical: Map<String, String>) {
        candidates.filter { exists(it.file) }.forEach { candidate ->
            val source = try { read(candidate.file) } catch (_: Exception) { null } ?: return@forEach
            if (source.all { (key, value) -> canonical[key] == value }) deleteSource(candidate)
        }
    }

    private fun deleteSource(candidate: Candidate) {
        val base = candidate.file.baseFile
        base.delete()
        File(base.path + ".bak").delete()
    }

    private fun read(atomicFile: AtomicFile): Map<String, String> {
        return atomicFile.openRead().use { input -> read(input) }
    }

    private fun read(input: FileInputStream): Map<String, String> {
        require(input.channel.size() <= ScriptLimits.MAX_BYTES) { "GM storage exceeds 10 MiB" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
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
            return result
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

    private fun serialize(map: Map<String, String>): String = buildString {
        append('{')
        map.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append(JSONObject.quote(key)); append(':'); append(value)
        }
        append('}')
    }

    companion object {
        private fun hashOf(identity: String) = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

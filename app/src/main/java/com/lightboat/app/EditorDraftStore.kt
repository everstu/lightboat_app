package com.lightboat.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Large editor state stays in private storage; the activity Bundle holds only its ID. */
internal class EditorDraftStore(context: Context) {
    private val directory = File(context.filesDir, "editor-drafts")
    private fun file(id: String): AtomicFile {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return AtomicFile(File(directory, "$id.json"))
    }

    fun save(id: String?, library: List<SharedScript>, references: List<ProfileScript>): String {
        val key = id ?: UUID.randomUUID().toString()
        val target = file(key)
        val data = JSONObject().put("library", JSONObject(ConfigCodec.encode(AppConfig.EMPTY.copy(scriptLibrary = library))))
            .put("references", ConfigCodec.referencesJson(references)).toString().toByteArray(Charsets.UTF_8)
        val output = target.startWrite()
        try { output.write(data); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
        return key
    }

    fun load(id: String): Pair<List<SharedScript>, List<ProfileScript>> {
        val target = file(id)
        val text = target.openRead().use { input ->
            require(input.channel.size() <= 2 * 1024 * 1024) { "编辑草稿过大" }
            input.bufferedReader(Charsets.UTF_8).readText()
        }
        val data = JSONObject(text)
        val library = ConfigCodec.decode(data.getJSONObject("library").toString()).scriptLibrary
        return library to ConfigCodec.decodeReferences(data.getJSONArray("references"), library)
    }

    fun delete(id: String?) { if (id != null) runCatching { file(id).delete() } }
}

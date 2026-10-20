package com.lightboat.app.userscript

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.math.BigDecimal

/** One wire/storage format for both live GM writes and backup validation. */
internal object ScriptValueCodec {
    fun validate(encoded: String) = JsonReader(StringReader(encoded)).use { reader ->
        validate(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "存储值包含多余内容" }
    }

    fun read(reader: JsonReader): String {
        val text = StringWriter()
        JsonWriter(text).use { copyValue(reader, it) }
        return text.toString()
    }

    fun validate(reader: JsonReader) {
        // Backups need validation, not a second full encoded copy of each value.
        val sink = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        JsonWriter(sink).use { copyValue(reader, it) }
    }

    private fun copyValue(reader: JsonReader, writer: JsonWriter) {
        reader.beginObject(); writer.beginObject()
        val fields = mutableSetOf<String>()
        var type: String? = null
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field)) { "存储值字段重复" }
            writer.name(field)
            when (field) {
                "type" -> {
                    require(reader.peek() == JsonToken.STRING)
                    type = reader.nextString(); writer.value(type)
                }
                "value" -> copyJson(reader, writer, 0)
                else -> error("存储值字段无效")
            }
        }
        reader.endObject(); writer.endObject()
        require(type == "json" && "value" in fields || type == "undefined" && "value" !in fields) { "存储值格式无效" }
    }

    private fun copyJson(reader: JsonReader, writer: JsonWriter, depth: Int) {
        require(depth < 128) { "GM 存储嵌套过深" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject(); writer.beginObject()
                val fields = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(fields.add(name)) { "JSON 字段重复" }
                    writer.name(name); copyJson(reader, writer, depth + 1)
                }
                reader.endObject(); writer.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray(); writer.beginArray()
                while (reader.hasNext()) copyJson(reader, writer, depth + 1)
                reader.endArray(); writer.endArray()
            }
            JsonToken.STRING -> writer.value(reader.nextString())
            JsonToken.NUMBER -> writer.value(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
            else -> error("GM 存储 JSON 无效")
        }
    }
}

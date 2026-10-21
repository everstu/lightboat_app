package com.lightboat.app.userscript

import org.json.JSONObject

/**
 * Frames bridge messages whose value is already encoded JSON text.
 *
 * Values can be several MiB, so they are spliced between fixed parts instead of
 * being parsed and re-serialized. Every brace must therefore be written
 * explicitly here: [value] and [valueResult] return the open prefix, the raw
 * value, and the exact closing suffix. Unit tests parse the joined parts, so a
 * mismatched brace or an unescaped header field fails the build without needing
 * a device.
 */
internal object ScriptMessageFraming {
    /** `{"doc":…,"event":…,"key":…,"deleted":…,"value":<value>}` */
    fun value(doc: String, event: String, key: String, value: String?, deleted: Boolean): List<String> {
        val header = buildString {
            append('{')
            append("\"doc\":").append(JSONObject.quote(doc))
            append(",\"event\":").append(JSONObject.quote(event))
            append(",\"key\":").append(JSONObject.quote(key))
            append(",\"deleted\":").append(deleted)
            append(",\"value\":")
        }
        return listOf(header, value ?: "null", "}")
    }

    /**
     * `{"doc":…,"id":…,"result":{"present":…,"value":<value>}}`
     *
     * The runtime resolves `message.result`, and lazy `GM.getValue` reads both
     * `result.present` and `result.value`; `value` must stay inside `result`.
     */
    fun valueResult(doc: String, id: String, value: String?): List<String> {
        val header = buildString {
            append('{')
            append("\"doc\":").append(JSONObject.quote(doc))
            append(",\"id\":").append(JSONObject.quote(id))
            append(",\"result\":{\"present\":").append(value != null)
            // The value object stays open here and is closed by the final `}}`.
            append(",\"value\":")
        }
        return listOf(header, value ?: "null", "}}")
    }
}

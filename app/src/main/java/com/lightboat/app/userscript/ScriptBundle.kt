package com.lightboat.app.userscript

import org.json.JSONArray
import org.json.JSONObject

data class ScriptBundle(
    val source: String,
    val metadata: ScriptMetadata,
    val requirements: List<String>,
    val resources: JSONObject
) {
    fun bootstrap(runtime: String, token: String, scriptUrl: String): String {
        val spec = JSONObject().put("meta", metadata.json()).put("token", token).put("values", JSONObject())
            .put("resources", resources).put("url", scriptUrl).put("parameters", apiJson())
        val body = (requirements + source).joinToString("\n;\n")
        // Compile with the injected document-start source, not eval/Function at runtime.
        // Parameters keep each granted API private to this script's lexical scope.
        return "(function(){const spec=$spec;\n$runtime\n" +
            "LightboatRuntime(spec,function(${API_PARAMETERS.joinToString(",")}){\n$body\n});\n})();\n" +
            "//# sourceURL=lightboat-userscript.js"
    }

    companion object {
        val API_PARAMETERS = listOf("GM", "GM_info", "unsafeWindow", "GM_getValue", "GM_setValue", "GM_deleteValue",
            "GM_listValues", "GM_getValues", "GM_setValues", "GM_deleteValues", "GM_addValueChangeListener",
            "GM_removeValueChangeListener", "GM_addStyle", "GM_addElement", "GM_getResourceText", "GM_getResourceURL",
            "GM_getResourceUrl", "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_xmlhttpRequest",
            "GM_setClipboard", "GM_log", "GM_notification", "GM_openInTab")
        fun apiJson() = JSONArray(API_PARAMETERS)
    }
}

package com.lightboat.app.userscript

import org.json.JSONArray
import org.json.JSONObject

data class ScriptBundle(
    val source: String,
    val metadata: ScriptMetadata,
    val requirements: List<String>,
    val resources: JSONObject
) {
    fun bootstrap(runtime: String, token: String, scriptUrl: String, bridgeName: String = "LightboatGM",
        sourceName: String = "lightboat-userscript.js"): String {
        val spec = JSONObject().put("meta", metadata.json()).put("token", token).put("values", JSONObject())
            .put("resources", resources).put("url", scriptUrl).put("bridgeName", bridgeName).put("parameters", apiJson())
        val body = (requirements + source).joinToString("\n;\n")
        // Compile with the injected document-start source, not eval/Function at runtime.
        // Parameters keep each granted API private to this script's lexical scope.
        // The marker records synchronous runner setup, including when its entry runs later.
        return "(function(){const spec=$spec;\n$runtime\n" +
            "LightboatRuntime(spec,function(${API_PARAMETERS.joinToString(",")}){\n$body\n});\n" +
            "window[${JSONObject.quote(bridgeName + "_initialized")}]=true;\n})();\n" +
            "//# sourceURL=$sourceName"
    }

    fun initializationCheck(matcher: String, token: String, bridgeName: String): String = """
        (function(){
        $matcher
        const bridge=window[${JSONObject.quote(bridgeName)}];
        const send=bridge.postMessage.bind(bridge);
        const stringify=JSON.stringify.bind(JSON),create=Object.create.bind(Object),assign=Object.assign.bind(Object);
        const marker=${JSONObject.quote(bridgeName + "_initialized")};
        setTimeout(function(){
          const initialized=window[marker]===true;
          delete window[marker];
          if(initialized || !LightboatMatches(${metadata.json()},location.href,window===window.top)) return;
          try { send(stringify(assign(create(null),{
            token:${JSONObject.quote(token)},doc:Date.now()+'-'+Math.random(),url:location.href,
            op:'bootstrapFailed',text:'脚本未能完成初始化，可能存在语法或启动错误。请查看调试日志。'
          }))); } catch (_) {}
        },0);
        })();
    """.trimIndent()

    companion object {
        val API_PARAMETERS = listOf("GM", "GM_info", "unsafeWindow", "GM_getValue", "GM_setValue", "GM_deleteValue",
            "GM_listValues", "GM_getValues", "GM_setValues", "GM_deleteValues", "GM_addValueChangeListener",
            "GM_removeValueChangeListener", "GM_addStyle", "GM_addElement", "GM_getResourceText", "GM_getResourceURL",
            "GM_getResourceUrl", "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_xmlhttpRequest",
            "GM_setClipboard", "GM_log", "GM_notification", "GM_openInTab")
        fun apiJson() = JSONArray(API_PARAMETERS)
    }
}

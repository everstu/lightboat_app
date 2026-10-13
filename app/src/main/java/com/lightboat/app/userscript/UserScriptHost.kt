package com.lightboat.app.userscript

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.lightboat.app.AppWebView
import com.lightboat.app.Profile
import org.json.JSONObject
import java.net.URI
import java.util.UUID

class UserScriptHost(
    private val activity: Activity,
    private val view: AppWebView,
    private val profile: Profile,
    private val bundle: ScriptBundle
) {
    data class Menu(val doc: String, val id: String, val title: String)
    private data class Session(val url: String, val reply: JavaScriptReplyProxy)
    private val token = UUID.randomUUID().toString()
    private val store = ScriptValueStore(activity, profile.id + ":" + bundle.metadata.first("namespace") + ":" + bundle.metadata.name)
    private val network = ScriptNetwork(bundle.metadata, profile.userAgent)
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = linkedMapOf<String, Session>()
    private val commands = linkedMapOf<String, Menu>()
    private val logs = ArrayDeque<String>()
    private var alive = true
    val menus get() = commands.values.toList()
    fun logText() = logs.joinToString("\n").ifEmpty { "暂无脚本日志" }

    fun log(level: String, text: String) {
        if (logs.size >= 100) logs.removeFirst()
        logs.addLast("[$level] ${text.take(8000)}")
    }

    fun console(message: ConsoleMessage) {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.sourceId().contains("lightboat-userscript")) {
            log(message.messageLevel().name, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
        }
    }

    fun install(): ScriptHandler {
        val meta = bundle.metadata
        log("info", "${meta.name}：${meta.runAt}；授权 ${meta.grants.joinToString().ifEmpty { "无" }}")
        if (!meta.isUserscript) log("info", "普通 JS 使用 document-start")
        if (meta.isUserscript && !meta.matchesPage(profile.url)) log("warning", "入口网址不符合脚本匹配规则，当前入口不会运行脚本")
        WebViewCompat.addWebMessageListener(view, "LightboatGM", setOf("*")) { sender, message, origin, main, reply ->
            if (!alive || sender !== view || message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 2 * 1024 * 1024) return@addWebMessageListener
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
            if (payload.optString("token") != token) return@addWebMessageListener
            val doc = payload.optString("doc")
            if (doc.isEmpty() || doc.length > 100) return@addWebMessageListener
            val address = payload.optString("url")
            val uri = runCatching { URI(address) }.getOrNull() ?: return@addWebMessageListener
            if (uri.scheme !in listOf("http", "https") || uri.host == null || !sameOrigin(uri, origin) || (meta.noFrames && !main)) return@addWebMessageListener
            if (!meta.matchesPage(address)) return@addWebMessageListener
            val op = payload.optString("op")
            val id = payload.optString("id")
            if (id.length > 100) return@addWebMessageListener
            fun respond(result: Any? = null, error: String? = null) {
                val response = JSONObject().put("doc", doc).put("id", id)
                if (error != null) response.put("error", error) else response.put("result", result ?: JSONObject.NULL)
                runCatching { reply.postMessage(response.toString()) }
            }
            try {
                if (op == "hello") {
                    require(sessions.size < 100) { "脚本 frame 数量超过上限" }
                    sessions[doc] = Session(address, reply)
                    val readsValues = meta.grants.any { it.contains("Value") }
                    respond(if (readsValues && "none" !in meta.grants) store.snapshot() else JSONObject())
                    return@addWebMessageListener
                }
                val session = sessions[doc] ?: error("脚本文档已失效")
                require(sameOrigin(URI(session.url), origin)) { "脚本来源不一致" }
                if (op == "log") { log(payload.optString("level", "info"), payload.optString("text")); return@addWebMessageListener }
                if (op == "dispose") { dispose(doc); return@addWebMessageListener }
                if (op == "abort") { network.abort("$doc:${payload.getString("requestId")}"); return@addWebMessageListener }
                val permission = when (op) {
                    "setValue" -> listOf("GM_setValue", "GM.setValue", "GM_setValues", "GM.setValues")
                    "deleteValue" -> listOf("GM_deleteValue", "GM.deleteValue", "GM_deleteValues", "GM.deleteValues")
                    "menuRegister" -> listOf("GM_registerMenuCommand", "GM.registerMenuCommand")
                    "menuRemove" -> listOf("GM_unregisterMenuCommand", "GM.unregisterMenuCommand")
                    "clipboard" -> listOf("GM_setClipboard", "GM.setClipboard")
                    "notification" -> listOf("GM_notification", "GM.notification")
                    "xhr" -> listOf("GM_xmlhttpRequest", "GM.xmlHttpRequest")
                    else -> error("不支持 GM 操作 $op")
                }
                require(permission.any { meta.permits(it) }) { "@grant 未授权 $op" }
                when (op) {
                    "setValue", "deleteValue" -> {
                        val key = payload.getString("key")
                        val value = if (op == "deleteValue") null else payload.getJSONObject("value")
                        if (value != null) require(value.optString("type") in setOf("json", "undefined")) { "存储值格式无效" }
                        store.update(key, value)
                        sessions.filterKeys { it != doc }.forEach { (other, recipient) ->
                            runCatching { recipient.reply.postMessage(JSONObject().put("doc", other).put("event", "value")
                                .put("key", key).put("value", value ?: JSONObject.NULL).put("deleted", value == null).toString()) }
                        }
                        respond()
                    }
                    "menuRegister" -> {
                        require(commands.size < 100) { "脚本菜单数量超过上限" }
                        val menuId = payload.getString("menuId").take(100)
                        commands["$doc:$menuId"] = Menu(doc, menuId, payload.getString("title").take(100))
                        respond()
                    }
                    "menuRemove" -> { commands.remove("$doc:${payload.getString("menuId")}"); respond() }
                    "clipboard" -> {
                        check(view.isShown && activity.hasWindowFocus() && view.consumeClipboardGesture()) { "复制需要用户点击操作" }
                        val text = payload.getString("text")
                        require(text.length <= 65536) { "复制文本过长" }
                        (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(meta.name, text))
                        respond()
                    }
                    "notification" -> { Toast.makeText(activity, payload.getString("text").take(2000), Toast.LENGTH_LONG).show(); respond() }
                    "xhr" -> network.execute("$doc:$id", address, payload) { kind, result, error ->
                        handler.post {
                            if (alive && sessions[doc] === session) {
                                val response = JSONObject().put("doc", doc).put("id", id).put("event", kind)
                                if (error != null) { response.put("error", error); log("error", "GM request: $error") }
                                if (result != null) response.put("result", result)
                                runCatching { reply.postMessage(response.toString()) }
                            }
                        }
                    }
                }
            } catch (error: Exception) { log("error", error.message.orEmpty()); respond(error = error.message ?: "GM 调用失败") }
        }
        if (!meta.isUserscript) return WebViewCompat.addDocumentStartJavaScript(view, bundle.source + "\n//# sourceURL=lightboat-userscript.js", setOf("*"))
        val matcher = activity.assets.open("userscript-matcher.js").bufferedReader().use { it.readText() }
        val runtime = activity.assets.open("userscript-runtime.js").bufferedReader().use { it.readText() }
        return WebViewCompat.addDocumentStartJavaScript(view, bundle.bootstrap("$matcher\n$runtime", token, profile.scriptUrl), setOf("*"))
    }

    fun invoke(menu: Menu) {
        view.markClipboardGesture()
        sessions[menu.doc]?.let { session ->
            runCatching { session.reply.postMessage(JSONObject().put("doc", menu.doc).put("event", "menu").put("menuId", menu.id).toString()) }
        }
    }

    private fun dispose(doc: String) {
        sessions.remove(doc)
        commands.keys.filter { it.startsWith("$doc:") }.forEach { commands.remove(it) }
        network.abortDocument(doc)
    }

    fun resetDocuments() { sessions.keys.toList().forEach { dispose(it) } }

    fun close() { alive = false; network.close(); sessions.clear(); commands.clear(); handler.removeCallbacksAndMessages(null) }

    private fun sameOrigin(uri: URI, origin: Uri): Boolean {
        val uriPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        val originPort = if (origin.port >= 0) origin.port else if (origin.scheme == "https") 443 else 80
        return uri.scheme == origin.scheme && uri.host.equals(origin.host, true) && uriPort == originPort
    }
}

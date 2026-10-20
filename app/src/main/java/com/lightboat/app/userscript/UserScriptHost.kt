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
import com.lightboat.app.SharedScript
import com.lightboat.app.ProfileScript
import com.lightboat.app.DebugLog
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal object ScriptStorage {
    // One queue preserves write/read order even when a profile recreates its host.
    private val queue = ScriptStorageQueue()
    private val main = Handler(Looper.getMainLooper())
    private val cleanups = ArrayDeque<() -> Unit>() // Main-thread-only; no stored values.
    private val retryCleanup = Runnable { drainCleanups() }
    fun submit(task: () -> Unit) = queue.submit(task)
    /** Called by a background backup worker after the browser hosts have been closed. */
    fun <T> exclusive(action: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "不能在主线程等待脚本存储" }
        val result = java.util.concurrent.CompletableFuture<T>()
        check(submit { try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } }) {
            "脚本存储繁忙，请稍后重试"
        }
        // Once a restore is queued it must finish or roll back, even if the UI closes.
        var interrupted = false
        try {
            while (true) {
                try { return result.get() }
                catch (_: InterruptedException) { interrupted = true }
                catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    fun cleanup(task: () -> Unit) {
        // A retired document must release its upload even if the request queue is full.
        // This handler outlives individual hosts; their close() cannot cancel the retry.
        cleanups.addLast(task)
        drainCleanups()
    }
    private fun drainCleanups() {
        main.removeCallbacks(retryCleanup)
        while (cleanups.isNotEmpty()) {
            val task = cleanups.first()
            if (!submit { runCatching(task) }) { main.postDelayed(retryCleanup, 100L); return }
            cleanups.removeFirst()
        }
    }
}

private object UserScriptAssets {
    private var matcher: String? = null
    private var runtime: String? = null

    @Synchronized
    fun load(activity: Activity): Pair<String, String> {
        val cachedMatcher = matcher ?: activity.applicationContext.assets.open("userscript-matcher.js")
            .bufferedReader().use { it.readText() }.also { matcher = it }
        val cachedRuntime = runtime ?: activity.applicationContext.assets.open("userscript-runtime.js")
            .bufferedReader().use { it.readText() }.also { runtime = it }
        return cachedMatcher to cachedRuntime
    }
}

class UserScriptHost(
    private val activity: Activity,
    private val view: AppWebView,
    private val profile: Profile,
    private val bundle: ScriptBundle,
    private val script: SharedScript,
    reference: ProfileScript,
    private val onExecutionFailure: (String) -> Unit = {},
) {
    data class Menu(val doc: String, val id: String, val title: String)
    private class Session(val url: String, val reply: JavaScriptReplyProxy, val transport: ScriptMessageTransport) {
        @Volatile var closed = false
        var upload: ScriptValueUpload? = null // Serial storage-worker access only.
        var uploadId: String? = null
    }

    companion object {
        // Single operation table: adding an operation only extends this map and the dispatch below.
        private val VALUE_WRITES = listOf("GM_setValue", "GM.setValue", "GM_setValues", "GM.setValues")
        private val OPERATIONS: Map<String, List<String>> = mapOf(
            "setValue" to VALUE_WRITES, "valueBegin" to VALUE_WRITES, "valueChunk" to VALUE_WRITES,
            "deleteValue" to listOf("GM_deleteValue", "GM.deleteValue", "GM_deleteValues", "GM.deleteValues"),
            "menuRegister" to listOf("GM_registerMenuCommand", "GM.registerMenuCommand"),
            "menuRemove" to listOf("GM_unregisterMenuCommand", "GM.unregisterMenuCommand"),
            "clipboard" to listOf("GM_setClipboard", "GM.setClipboard"),
            "notification" to listOf("GM_notification", "GM.notification"),
            "xhr" to listOf("GM_xmlhttpRequest", "GM.xmlHttpRequest"),
        )
    }

    private val token = UUID.randomUUID().toString()
    private val bridgeName = "LightboatGM_" + token.replace("-", "")
    val consoleSource = "lightboat-userscript-" + UUID.nameUUIDFromBytes(script.id.toByteArray()).toString() + ".js"
    private val metadataIdentity = bundle.metadata.first("namespace") + ":" + bundle.metadata.name
    private val store = ScriptValueStore(activity, profile.id + ":" + script.id + ":" + metadataIdentity,
        if (reference.legacyStorage) profile.id + ":" + metadataIdentity else null)
    private val network = ScriptNetwork(bundle.metadata, profile.userAgent)
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = linkedMapOf<String, Session>()
    private val commands = linkedMapOf<String, Menu>()
    private val commandListeners = linkedSetOf<() -> Unit>()
    private val logs = ArrayDeque<String>()
    private var alive = true
    val menus get() = commands.values.toList()
    fun addCommandListener(listener: () -> Unit) { commandListeners.add(listener) }
    fun removeCommandListener(listener: () -> Unit) { commandListeners.remove(listener) }
    private fun commandsChanged() { commandListeners.toList().forEach { it() } }
    fun logText() = logs.joinToString("\n").ifEmpty { "暂无脚本日志" }

    fun log(level: String, text: String) {
        if (logs.size >= 100) logs.removeFirst()
        logs.addLast("[${script.name}][$level] ${text.take(8000)}")
        DebugLog.event("userscript", "$level [${script.name}] ${text.take(1000)}")
    }

    fun console(message: ConsoleMessage) {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.sourceId().contains("lightboat-userscript")) {
            log(message.messageLevel().name, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
        }
    }

    fun pageCommitted(url: String?) {
        if (url == null) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val address = "${uri.host}${uri.path}"
        if (!bundle.metadata.matchesPage(url)) {
            log("warning", "当前页面 $address 不符合 @match/@include，脚本不会执行；请核对脚本支持的网址")
        }
    }

    fun install(): List<ScriptHandler> {
        val meta = bundle.metadata
        log("info", "${meta.name}：${meta.runAt}；授权 ${meta.grants.joinToString().ifEmpty { "无" }}")
        if (!meta.isUserscript) log("info", "普通 JS 使用 document-start")
        if (meta.isUserscript && !meta.matchesPage(profile.url)) log("warning", "入口网址不符合脚本匹配规则，当前入口不会运行脚本")
        if (Regex("""\b(?:eval|Function)\s*\(""").containsMatchIn(bundle.source) || "WebAssembly.compile" in bundle.source) {
            log("warning", "脚本包含动态代码执行；已正常注入，但网页 CSP 可能阻止解密后的代码运行")
        }
        WebViewCompat.addWebMessageListener(view, bridgeName, setOf("*")) { sender, message, origin, main, reply ->
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
            if (op == "bootstrapFailed") {
                val text = payload.optString("text").take(1000)
                log("error", text)
                onExecutionFailure(text)
                return@addWebMessageListener
            }
            fun respond(result: Any? = null, error: String? = null) {
                val response = JSONObject().put("doc", doc).put("id", id)
                if (error != null) response.put("error", error) else response.put("result", result ?: JSONObject.NULL)
                runCatching { reply.postMessage(response.toString()) }
            }
            fun storageTask(session: Session, task: () -> Any?, success: (Any?) -> Unit = { respond(it) }) {
                val accepted = ScriptStorage.submit {
                    val result = runCatching(task)
                    handler.post {
                        if (!alive || sessions[doc] !== session) return@post
                        result.fold(onSuccess = success, onFailure = { error ->
                            log("error", error.message.orEmpty())
                            respond(error = error.message ?: "GM 存储失败")
                        })
                    }
                }
                if (!accepted) {
                    log("error", "GM 存储繁忙，请稍后重试")
                    respond(error = "GM 存储繁忙，请稍后重试")
                }
            }
            try {
                if (op == "hello") {
                    // Document callbacks and frame messages have independent timing.
                    // A handshake may replace only the same document's session.
                    if (sessions.containsKey(doc)) dispose(doc)
                    require(sessions.size < 100) { "脚本 frame 数量超过上限" }
                    val session = Session(address, reply, ScriptMessageTransport(doc, reply, handler))
                    sessions[doc] = session
                    val readsValues = meta.grants.any { it.contains("Value") }
                    if (readsValues && "none" !in meta.grants) storageTask(session, {
                        store.forEachEncoded { key, value ->
                            if (!session.closed) session.transport.sendValue("snapshotValue", key, value)
                        }
                        JSONObject()
                    })
                    else respond(JSONObject())
                    return@addWebMessageListener
                }
                val session = sessions[doc] ?: error("脚本文档已失效")
                require(sameOrigin(URI(session.url), origin)) { "脚本来源不一致" }
                if (op == "executionFailed") {
                    onExecutionFailure(payload.optString("text").take(1000))
                    return@addWebMessageListener
                }
                if (op == "chunkAck") { session.transport.acknowledge(payload.getString("transfer"), payload.getInt("index")); return@addWebMessageListener }
                if (op == "log") { log(payload.optString("level", "info"), payload.optString("text")); return@addWebMessageListener }
                if (op == "dispose") { dispose(doc); return@addWebMessageListener }
                if (op == "abort") { network.abort("$doc:${payload.getString("requestId")}"); return@addWebMessageListener }
                if (op == "valueAbort") {
                    ScriptStorage.cleanup { session.upload?.close(); session.upload = null; session.uploadId = null }
                    return@addWebMessageListener
                }
                val permission = OPERATIONS[op] ?: error("不支持 GM 操作 $op")
                require(permission.any { meta.permits(it) }) { "@grant 未授权 $op" }
                fun broadcast(recipients: List<Session>, key: String, value: String?) {
                    // Run within the write task, before its acknowledgement and the next
                    // write. No second queue submission can reject or reorder notifications.
                    recipients.forEach { recipient ->
                        if (!recipient.closed) runCatching { recipient.transport.sendValue("value", key, value, value == null) }
                    }
                }
                when (op) {
                    "valueBegin" -> {
                        val key = payload.getString("key")
                        val length = payload.getInt("characters")
                        require(key.length <= 512 && length in 1..ScriptLimits.MAX_BYTES) { "GM 存储值不能超过 10 MiB" }
                        storageTask(session, {
                            check(!session.closed)
                            session.upload?.close()
                            session.upload = ScriptValueUpload(activity.cacheDir, key, length)
                            UUID.randomUUID().toString().also { session.uploadId = it }
                        }) { result ->
                            respond(result)
                            handler.postDelayed({
                                ScriptStorage.cleanup {
                                    if (session.uploadId == result) { session.upload?.close(); session.upload = null; session.uploadId = null }
                                }
                            }, 120000)
                        }
                    }
                    "valueChunk" -> {
                        val recipients = sessions.filterKeys { it != doc }.values.toList()
                        storageTask(session, {
                            check(!session.closed)
                            require(payload.getString("transfer") == session.uploadId) { "GM 存储传输已失效" }
                            val upload = session.upload ?: error("GM 存储传输不存在")
                            try {
                                val value = upload.append(payload.getInt("index"), payload.getString("text"), payload.getBoolean("last"))
                                if (value != null) {
                                    store.updateEncoded(upload.key, value)
                                    upload.close(); session.upload = null; session.uploadId = null
                                    broadcast(recipients, upload.key, value)
                                }
                                null
                            } catch (error: Exception) { upload.close(); session.upload = null; session.uploadId = null; throw error }
                        })
                    }
                    "setValue", "deleteValue" -> {
                        val key = payload.getString("key")
                        val value = if (op == "deleteValue") null else payload.getJSONObject("value")
                        if (value != null) require(value.optString("type") in setOf("json", "undefined")) { "存储值格式无效" }
                        val encoded = value?.toString()
                        val recipients = sessions.filterKeys { it != doc }.values.toList()
                        storageTask(session, {
                            check(!session.closed)
                            store.updateEncoded(key, encoded)
                            broadcast(recipients, key, encoded)
                            null
                        })
                    }
                    "menuRegister" -> {
                        require(commands.size < 100) { "脚本菜单数量超过上限" }
                        val menuId = payload.getString("menuId").take(100)
                        commands["$doc:$menuId"] = Menu(doc, menuId, payload.getString("title").take(100))
                        commandsChanged()
                        respond()
                    }
                    "menuRemove" -> { commands.remove("$doc:${payload.getString("menuId")}"); commandsChanged(); respond() }
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
                        if (kind == "chunk") {
                            if (!session.closed && result != null) session.transport.send(JSONObject().put("doc", doc).put("id", id)
                                .put("event", "xhrChunk").put("result", result))
                            return@execute
                        }
                        val status = result?.optInt("status", 0) ?: 0
                        DebugLog.event("gm-xhr", "event=$kind status=$status url=${DebugLog.safeUrl(address)} ok=${error == null} error=${error.orEmpty().take(180)}")
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
        val (matcher, runtime) = UserScriptAssets.load(activity)
        val source = if (meta.isUserscript) bundle.bootstrap("$matcher\n$runtime", token, script.url, bridgeName, consoleSource)
            else bundle.source + "\n;\nwindow[${JSONObject.quote(bridgeName + "_initialized")}]=true;\n//# sourceURL=$consoleSource"
        val installed = mutableListOf<ScriptHandler>()
        try {
            // Chromium can attribute parse errors to the page instead of sourceURL.
            // A separate source observes initialization without eval or changing raw JS scope.
            installed += WebViewCompat.addDocumentStartJavaScript(view, bundle.initializationCheck(matcher, token, bridgeName), setOf("*"))
            installed += WebViewCompat.addDocumentStartJavaScript(view, source, setOf("*"))
            return installed
        } catch (error: Exception) {
            installed.forEach { runCatching { it.remove() } }
            throw error
        }
    }

    fun invoke(menu: Menu) {
        view.markClipboardGesture()
        sessions[menu.doc]?.let { session ->
            runCatching { session.reply.postMessage(JSONObject().put("doc", menu.doc).put("event", "menu").put("menuId", menu.id).toString()) }
        }
    }

    private fun dispose(doc: String) {
        sessions.remove(doc)?.let { session ->
            session.closed = true
            session.transport.close()
            ScriptStorage.cleanup { session.upload?.close(); session.upload = null; session.uploadId = null }
        }
        val removed = commands.keys.removeAll { it.startsWith("$doc:") }
        if (removed) commandsChanged()
        network.abortDocument(doc)
    }

    fun resetDocuments() { sessions.keys.toList().forEach { dispose(it) } }

    fun close() {
        alive = false
        network.close()
        sessions.keys.toList().forEach(::dispose)
        commands.clear(); commandsChanged(); commandListeners.clear(); handler.removeCallbacksAndMessages(null)
        runCatching { WebViewCompat.removeWebMessageListener(view, bridgeName) }
    }

    private fun sameOrigin(uri: URI, origin: Uri): Boolean {
        val uriPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        val originPort = if (origin.port >= 0) origin.port else if (origin.scheme == "https") 443 else 80
        return uri.scheme == origin.scheme && uri.host.equals(origin.host, true) && uriPort == originPort
    }
}

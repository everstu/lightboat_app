package com.lightboat.app.userscript

import android.app.Activity
import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import androidx.webkit.ScriptHandler
import com.lightboat.app.AppWebView
import com.lightboat.app.Profile
import com.lightboat.app.SharedScript
import com.lightboat.app.DebugLog

data class PreparedScript(val script: SharedScript, val bundle: ScriptBundle)
class ScriptInstallationException(val script: SharedScript, cause: Exception) : Exception(cause.message, cause)

/** One native host/bridge per script; the browser UI sees one combined command list. */
class UserScriptGroup(activity: Activity, view: AppWebView, profile: Profile, prepared: List<PreparedScript>,
    private val onExecutionFailure: (SharedScript, String) -> Unit = { _, _ -> }) {
    data class Menu(val host: UserScriptHost, val command: UserScriptHost.Menu, val title: String)
    private val reportedFailures = mutableSetOf<String>()
    private fun executionFailed(script: SharedScript, message: String) {
        if (reportedFailures.add(script.id)) onExecutionFailure(script, message)
    }
    private val hosts = prepared.map { item -> item.script to UserScriptHost(activity, view, profile, item.bundle,
        item.script, profile.scripts.first { it.scriptId == item.script.id }) { message -> executionFailed(item.script, message) } }
    val menus get() = hosts.flatMap { (script, host) -> host.menus.map { Menu(host, it, "${script.name} · ${it.title}") } }
    @SuppressLint("RequiresFeature") // MainActivity checks DOCUMENT_START_SCRIPT before preparing any group.
    fun install(): List<ScriptHandler> {
        val installed = mutableListOf<ScriptHandler>()
        try {
            hosts.forEach { (script, host) ->
                try { installed += host.install() }
                catch (error: Exception) { throw ScriptInstallationException(script, error) }
            }
            return installed
        } catch (error: Exception) {
            installed.forEach { runCatching { it.remove() } }
            hosts.forEach { runCatching { it.second.close() } }
            throw error
        }
    }
    fun addCommandListener(listener: () -> Unit) = hosts.forEach { it.second.addCommandListener(listener) }
    fun removeCommandListener(listener: () -> Unit) = hosts.forEach { it.second.removeCommandListener(listener) }
    fun invoke(menu: Menu) { if (hosts.any { it.second === menu.host }) menu.host.invoke(menu.command) }
    fun console(message: ConsoleMessage) {
        val host = hosts.firstOrNull { message.sourceId().contains(it.second.consoleSource) }?.second
        if (host != null) {
            host.console(message)
        }
        else if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
            DebugLog.event("web-console", "${message.message().take(1000)} (${message.sourceId()}:${message.lineNumber()})")
    }
    fun pageCommitted(url: String?) = hosts.forEach { it.second.pageCommitted(url) }
    fun resetDocuments() = hosts.forEach { it.second.resetDocuments() }
    fun close() = hosts.forEach { it.second.close() }
}

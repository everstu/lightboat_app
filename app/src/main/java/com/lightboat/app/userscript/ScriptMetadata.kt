package com.lightboat.app.userscript

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class ScriptMetadata(val directives: Map<String, List<String>>, val header: String) {
    val isUserscript get() = header.isNotEmpty()
    fun all(key: String) = directives[key].orEmpty()
    fun first(key: String, fallback: String = "") = all(key).firstOrNull() ?: fallback
    val grants get() = all("grant").toSet()
    val name get() = first("name", "用户脚本")
    val runAt get() = first("run-at", if (isUserscript) "document-idle" else "document-start")
    val noFrames get() = directives.containsKey("noframes")

    fun json(): JSONObject = JSONObject().apply {
        directives.forEach { (key, values) -> put(key, JSONArray(values)) }
        put("name", name)
        put("runAt", runAt)
        put("noFrames", noFrames)
        put("header", header)
    }

    fun permits(api: String) = api in grants && "none" !in grants

    fun matchesPage(address: String): Boolean {
        val uri = runCatching { URI(address) }.getOrNull() ?: return false
        if (uri.scheme !in listOf("http", "https") || uri.host == null) return false
        val withoutHash = address.substringBefore('#')
        fun glob(pattern: String, text: String): Boolean = pattern.split('*').joinToString(".*") { Regex.escape(it) }
            .let { Regex("^$it$").matches(text) }
        fun include(pattern: String): Boolean {
            if (pattern.startsWith('/') && pattern.endsWith('/') && pattern.length >= 2) {
                return runCatching { Regex(pattern.substring(1, pattern.length - 1)).containsMatchIn(withoutHash) }.getOrDefault(false)
            }
            return glob(pattern, withoutHash)
        }
        fun match(pattern: String): Boolean {
            val rule = Regex("^(\\*|http\\*|https?)://([^/]+)(/.*)$").matchEntire(pattern) ?: return false
            val (scheme, domain, path) = rule.destructured
            if (scheme !in listOf("*", "http*") && scheme != uri.scheme) return false
            val host = uri.host.lowercase()
            val hostname = domain.lowercase()
            val authority = if (uri.port >= 0) "$host:${uri.port}" else host
            val allowed = hostname == "*" || hostname == host || hostname == authority ||
                (hostname.startsWith("*.") && (host == hostname.substring(2) || host.endsWith("." + hostname.substring(2))))
            return allowed && glob(path, (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: ""))
        }
        val allowed = (all("match").isEmpty() && all("include").isEmpty()) || all("match").any { match(it) } || all("include").any { include(it) }
        return allowed && !all("exclude").any { include(it) } && !all("exclude-match").any { match(it) }
    }

    fun permitsConnection(url: String, pageUrl: String): Boolean {
        val target = URI(url)
        if (target.scheme !in listOf("http", "https") || target.host == null || target.userInfo != null) return false
        val host = target.host.lowercase().removeSurrounding("[", "]")
        val self = URI(pageUrl).host?.lowercase()?.removeSurrounding("[", "]")
        // Same-origin requests need no additional @connect permission.
        if (target.scheme == URI(pageUrl).scheme && host == self && effectivePort(target) == effectivePort(URI(pageUrl))) return true
        return all("connect").any { rule ->
            val domain = rule.lowercase().removeSurrounding("[", "]")
            when (domain) {
                "*" -> true
                "self" -> host == self
                else -> host == domain || ('.' in domain && !domain.all { it.isDigit() || it == '.' } && host.endsWith(".$domain"))
            }
        }
    }

    companion object {
        private fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        fun sameOrigin(first: String, second: String): Boolean {
            val a = URI(first)
            val b = URI(second)
            return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
        }
        fun parse(source: String): ScriptMetadata {
            val match = Regex("(?m)^\\s*//\\s*==UserScript==\\s*$([\\s\\S]*?)^\\s*//\\s*==/UserScript==\\s*$").find(source)
                ?: return ScriptMetadata(emptyMap(), "")
            val directives = linkedMapOf<String, MutableList<String>>()
            match.groupValues[1].lineSequence().forEach { line ->
                Regex("^//\\s*@([\\w-]+)(?:\\s+(.*))?$").matchEntire(line.trim())?.let {
                    directives.getOrPut(it.groupValues[1]) { mutableListOf() }.add(it.groupValues[2].trim())
                }
            }
            val result = ScriptMetadata(directives, match.value)
            require(result.runAt in setOf("document-start", "document-body", "document-end", "document-idle")) {
                "不支持 @run-at ${result.runAt}"
            }
            require(result.all("require").size <= 16 && result.all("resource").size <= 32) { "脚本依赖/资源数量超过上限" }
            return result
        }
    }
}

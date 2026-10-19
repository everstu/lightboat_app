package com.lightboat.app

import java.util.UUID

/** Parse into a complete draft before replacing the library; never fetch script URLs. */
internal object ScriptBatchInput {
    data class Result(val library: List<SharedScript>, val added: Int, val duplicates: Int)

    fun addTo(library: List<SharedScript>, text: String): Result {
        require(text.length <= 1024 * 1024) { "批量内容过长，请分批添加" }
        var config = AppConfig.EMPTY.copy(scriptLibrary = library)
        val urls = library.mapTo(mutableSetOf()) { AppConfig.normalizeScriptUrl(it.url) }
        var added = 0
        var duplicates = 0
        var lines = 0
        text.lineSequence().forEachIndexed { index, input ->
            val line = input.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) return@forEachIndexed
            lines++
            require(lines <= AppConfig.MAX_LIBRARY_SCRIPTS) { "一次最多添加 ${AppConfig.MAX_LIBRARY_SCRIPTS} 行脚本" }
            try {
                val separator = line.indexOf('|')
                val rawName = if (separator < 0) "" else line.substring(0, separator)
                val url = AppConfig.normalizeScriptUrl(if (separator < 0) line else line.substring(separator + 1))
                val name = AppConfig.normalizeScriptName(rawName, url)
                if (!urls.add(url)) duplicates++ else {
                    config = config.upsertScript(SharedScript(UUID.randomUUID().toString(), name, url))
                    added++
                }
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${index + 1} 行：${error.message}", error)
            }
        }
        require(lines > 0) { "请填写至少一个脚本地址" }
        return Result(config.scriptLibrary, added, duplicates)
    }
}

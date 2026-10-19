package com.lightboat.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ConfigCodec {
    fun decode(text: String, defaultUserAgent: String = AppConfig.DEFAULT_USER_AGENT): AppConfig {
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(version in 1..2) { "不支持此配置文件版本" }
        val library = mutableListOf<SharedScript>()
        if (version == 2) {
            val scripts = root.getJSONArray("scriptLibrary")
            require(scripts.length() <= AppConfig.MAX_LIBRARY_SCRIPTS) { "脚本库数量超过上限" }
            for (i in 0 until scripts.length()) {
                val item = scripts.getJSONObject(i)
                val url = AppConfig.normalizeScriptUrl(item.getString("url"))
                val name = if (!item.has("name") || item.isNull("name")) "" else item.get("name").let {
                    require(it is String) { "脚本名称必须是字符串" }; it
                }
                val script = SharedScript(item.getString("id"), AppConfig.normalizeScriptName(name, url), url)
                require(script.id.isNotBlank() && script.id.length <= 100) { "脚本 ID 无效" }
                require(library.none { it.id == script.id || it.url == script.url }) { "脚本 ID 或地址不能重复" }
                library += script
            }
        }
        val categoryMap = if (root.has("categories")) root.getJSONObject("categories") else JSONObject()
        val categoryNames = java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
        val categories = categoryMap.keys().asSequence().associateWith { id ->
            require(id.isNotBlank() && id.length <= 100) { "分类 ID 无效" }
            val value = categoryMap.get(id)
            require(value is String) { "分类名称必须是字符串" }
            AppConfig.normalizeCategoryName(value).also {
                require(it.isNotEmpty()) { "分类名称不能为空" }
                require(categoryNames.add(it)) { "分类名称不能重复（忽略首尾空格与大小写）" }
            }
        }
        val array = root.getJSONArray("profiles")
        require(array.length() <= AppConfig.MAX_PROFILES) { "配置数量超过上限" }
        val profiles = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name").trim()
            require(id.isNotBlank() && id.length <= 100) { "配置 ID 无效" }
            require(name.isNotBlank() && name.length <= 60) { "配置名称须为 1–60 个字符" }
            val userAgent = AppConfig.normalizeUserAgent(
                if (item.has("userAgent")) item.getString("userAgent") else defaultUserAgent
            )
            val platform = if (item.has("platform")) BrowserPlatform.parse(item.getString("platform"))
                else MobileUserAgent.detect(userAgent)
            val scriptUrl = if (!item.has("scriptUrl") || item.isNull("scriptUrl")) ""
                else item.getString("scriptUrl").trim().let { if (it.isBlank()) "" else AppConfig.normalizeScriptUrl(it) }
            val references = if (version == 1) {
                if (scriptUrl.isBlank()) emptyList() else {
                    val shared = library.firstOrNull { it.url == scriptUrl } ?: SharedScript(
                        UUID.nameUUIDFromBytes(scriptUrl.toByteArray(Charsets.UTF_8)).toString(),
                        AppConfig.defaultScriptName(scriptUrl), scriptUrl
                    ).also { library += it }
                    listOf(ProfileScript(shared.id, legacyStorage = true))
                }
            } else {
                require(scriptUrl.isBlank()) { "新版配置请使用 scripts 引用，不再填写 scriptUrl" }
                decodeReferences(item.getJSONArray("scripts"), library)
            }
            val categoryId = if (!item.has("categoryId") || item.isNull("categoryId")) null
                else item.get("categoryId").let {
                    require(it is String && categories.containsKey(it)) { "配置引用的分类不存在" }
                    it
                }
            val favorite = if (!item.has("favorite")) false else {
                val value = item.get("favorite")
                require(value is Boolean) { "favorite 必须是布尔值" }
                value
            }
            Profile(id, name, AppConfig.normalizeUrl(item.getString("url")),
                references, userAgent, platform, categoryId, favorite)
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "配置 ID 不能重复" }
        require(root.has("lastProfileId")) { "缺少 lastProfileId" }
        val lastId = if (root.isNull("lastProfileId")) null else root.getString("lastProfileId")
        require(if (profiles.isEmpty()) lastId == null else profiles.any { it.id == lastId }) {
            "lastProfileId 必须指向现有配置"
        }
        return AppConfig(lastId, profiles, categories, library)
    }

    fun encode(config: AppConfig): String = JSONObject().apply {
        put("version", 2)
        put("lastProfileId", config.lastProfileId ?: JSONObject.NULL)
        put("categories", JSONObject().apply { config.categories.forEach { (id, name) -> put(id, name) } })
        put("scriptLibrary", JSONArray().apply { config.scriptLibrary.forEach { script ->
            put(JSONObject().put("id", script.id).put("name", script.name).put("url", script.url))
        } })
        put("profiles", JSONArray().apply {
            config.profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("url", profile.url)
                    put("scripts", referencesJson(profile.scripts))
                    put("userAgent", profile.userAgent)
                    put("platform", profile.platform.configValue)
                    put("categoryId", profile.categoryId ?: JSONObject.NULL)
                    put("favorite", profile.favorite)
                })
            }
        })
    }.toString(2)

    fun referencesJson(references: List<ProfileScript>) = JSONArray().apply { references.forEach { ref ->
        put(JSONObject().put("scriptId", ref.scriptId).put("enabled", ref.enabled).apply {
            if (ref.legacyStorage) put("legacyStorage", true)
        })
    } }

    fun decodeReferences(array: JSONArray, library: List<SharedScript>): List<ProfileScript> {
        require(array.length() <= AppConfig.MAX_PROFILE_SCRIPTS) { "配置脚本数量超过上限" }
        val result = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("scriptId")
            require(library.any { it.id == id }) { "配置引用的脚本不存在" }
            fun flag(name: String, fallback: Boolean): Boolean = if (!item.has(name)) fallback else {
                val value = item.get(name)
                require(value is Boolean) { "$name 必须是布尔值" }
                value
            }
            ProfileScript(id, flag("enabled", true), flag("legacyStorage", false))
        }
        require(result.map { it.scriptId }.distinct().size == result.size) { "同一配置不能重复引用脚本" }
        require(result.count { it.legacyStorage } <= 1) { "配置只能有一个旧版脚本存储" }
        return result
    }
}

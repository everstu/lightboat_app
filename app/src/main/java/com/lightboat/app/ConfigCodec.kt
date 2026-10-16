package com.lightboat.app

import org.json.JSONArray
import org.json.JSONObject

object ConfigCodec {
    fun decode(text: String, defaultUserAgent: String = AppConfig.DEFAULT_USER_AGENT): AppConfig {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "不支持此配置文件版本" }
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
                else AppConfig.normalizeOptionalUrl(item.getString("scriptUrl"))
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
                scriptUrl, userAgent, platform, categoryId, favorite)
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "配置 ID 不能重复" }
        require(root.has("lastProfileId")) { "缺少 lastProfileId" }
        val lastId = if (root.isNull("lastProfileId")) null else root.getString("lastProfileId")
        require(if (profiles.isEmpty()) lastId == null else profiles.any { it.id == lastId }) {
            "lastProfileId 必须指向现有配置"
        }
        return AppConfig(lastId, profiles, categories)
    }

    fun encode(config: AppConfig): String = JSONObject().apply {
        put("version", 1)
        put("lastProfileId", config.lastProfileId ?: JSONObject.NULL)
        put("categories", JSONObject().apply { config.categories.forEach { (id, name) -> put(id, name) } })
        put("profiles", JSONArray().apply {
            config.profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("url", profile.url)
                    put("scriptUrl", profile.scriptUrl)
                    put("userAgent", profile.userAgent)
                    put("platform", profile.platform.configValue)
                    put("categoryId", profile.categoryId ?: JSONObject.NULL)
                    put("favorite", profile.favorite)
                })
            }
        })
    }.toString(2)
}

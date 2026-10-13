package com.lightboat.app

import org.json.JSONArray
import org.json.JSONObject

object ConfigCodec {
    fun decode(text: String, defaultUserAgent: String = AppConfig.DEFAULT_USER_AGENT): AppConfig {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "不支持此配置文件版本" }
        val array = root.getJSONArray("profiles")
        require(array.length() <= AppConfig.MAX_PROFILES) { "配置数量超过上限" }
        val profiles = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name").trim()
            require(id.isNotBlank() && id.length <= 100) { "配置 ID 无效" }
            require(name.isNotBlank() && name.length <= 60) { "配置名称须为 1–60 个字符" }
            Profile(id, name, AppConfig.normalizeUrl(item.getString("url")),
                AppConfig.normalizeUrl(item.getString("scriptUrl")),
                AppConfig.normalizeUserAgent(if (item.has("userAgent")) item.getString("userAgent") else defaultUserAgent))
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "配置 ID 不能重复" }
        require(root.has("lastProfileId")) { "缺少 lastProfileId" }
        val lastId = if (root.isNull("lastProfileId")) null else root.getString("lastProfileId")
        require(if (profiles.isEmpty()) lastId == null else profiles.any { it.id == lastId }) {
            "lastProfileId 必须指向现有配置"
        }
        return AppConfig(lastId, profiles)
    }

    fun encode(config: AppConfig): String = JSONObject().apply {
        put("version", 1)
        put("lastProfileId", config.lastProfileId ?: JSONObject.NULL)
        put("profiles", JSONArray().apply {
            config.profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("url", profile.url)
                    put("scriptUrl", profile.scriptUrl)
                    put("userAgent", profile.userAgent)
                })
            }
        })
    }.toString(2)
}

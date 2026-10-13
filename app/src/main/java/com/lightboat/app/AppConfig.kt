package com.lightboat.app

import java.net.URI

data class Profile(val id: String, val name: String, val url: String, val scriptUrl: String)

data class AppConfig(val lastProfileId: String?, val profiles: List<Profile>) {
    val lastProfile: Profile? get() = profiles.firstOrNull { it.id == lastProfileId }

    fun upsert(profile: Profile): AppConfig {
        val next = if (profiles.any { it.id == profile.id }) {
            profiles.map { if (it.id == profile.id) profile else it }
        } else profiles + profile
        require(next.size <= MAX_PROFILES) { "最多支持 $MAX_PROFILES 组配置" }
        return AppConfig(if (profiles.isEmpty()) profile.id else lastProfileId, next)
    }

    fun remove(id: String): AppConfig {
        val next = profiles.filterNot { it.id == id }
        return AppConfig(if (lastProfileId == id) next.firstOrNull()?.id else lastProfileId, next)
    }

    fun select(id: String): AppConfig {
        require(profiles.any { it.id == id }) { "配置不存在" }
        return copy(lastProfileId = id)
    }

    companion object {
        const val MAX_PROFILES = 50
        val EMPTY = AppConfig(null, emptyList())

        fun normalizeUrl(value: String): String {
            val text = value.trim()
            require(text.isNotEmpty() && text.length <= 8192) { "请填写完整的 HTTP 或 HTTPS 地址" }
            val uri = try { URI(text) } catch (_: Exception) {
                throw IllegalArgumentException("地址无效，请检查域名、端口和格式")
            }
            require(uri.scheme?.lowercase() in setOf("http", "https")) {
                "请填写完整的 HTTP 或 HTTPS 地址"
            }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)) {
                "地址无效，请检查域名、端口和格式"
            }
            return uri.toASCIIString()
        }
    }
}

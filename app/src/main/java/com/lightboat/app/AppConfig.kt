package com.lightboat.app

import java.net.URI
import java.util.UUID

data class Profile(
    val id: String,
    val name: String,
    val url: String,
    val scriptUrl: String,
    val userAgent: String = AppConfig.DEFAULT_USER_AGENT,
    val platform: BrowserPlatform = MobileUserAgent.detect(userAgent),
    val categoryId: String? = null,
    val favorite: Boolean = false
)

data class AppConfig(
    val lastProfileId: String?,
    val profiles: List<Profile>,
    val categories: Map<String, String> = emptyMap()
) {
    val lastProfile: Profile? get() = profiles.firstOrNull { it.id == lastProfileId }

    fun upsert(profile: Profile): AppConfig {
        require(profile.categoryId == null || categories.containsKey(profile.categoryId)) { "配置引用的分类不存在" }
        val next = if (profiles.any { it.id == profile.id }) {
            profiles.map { if (it.id == profile.id) profile else it }
        } else profiles + profile
        require(next.size <= MAX_PROFILES) { "最多支持 $MAX_PROFILES 组配置" }
        return copy(lastProfileId = if (profiles.isEmpty()) profile.id else lastProfileId, profiles = next)
    }

    fun upsert(profile: Profile, categoryName: String): AppConfig {
        val name = normalizeCategoryName(categoryName)
        if (name.isEmpty()) return upsert(profile.copy(categoryId = null))
        val existingId = profile.categoryId?.takeIf { categories[it].equals(name, ignoreCase = true) }
            ?: categories.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
        val categoryId = existingId ?: UUID.randomUUID().toString()
        val next = if (existingId == null) copy(categories = categories + (categoryId to name)) else this
        return next.upsert(profile.copy(categoryId = categoryId))
    }

    fun remove(id: String): AppConfig {
        val next = profiles.filterNot { it.id == id }
        return copy(lastProfileId = if (lastProfileId == id) next.firstOrNull()?.id else lastProfileId, profiles = next)
    }

    fun select(id: String): AppConfig {
        require(profiles.any { it.id == id }) { "配置不存在" }
        return copy(lastProfileId = id)
    }

    fun reorder(ids: List<String>): AppConfig {
        require(ids.size == profiles.size && ids.toSet().size == ids.size && ids.toSet() == profiles.map { it.id }.toSet()) {
            "排序必须保留全部配置且不能重复"
        }
        val byId = profiles.associateBy { it.id }
        return copy(profiles = ids.map { byId.getValue(it) })
    }

    fun reorderVisible(ids: List<String>, filter: ProfileFilter): AppConfig {
        val visible = filter.apply(this).map { it.id }.toSet()
        require(ids.size == visible.size && ids.toSet() == visible) { "排序必须保留当前筛选的全部配置且不能重复" }
        val byId = profiles.associateBy { it.id }
        val ordered = ids.iterator()
        return copy(profiles = profiles.map { if (it.id in visible) byId.getValue(ordered.next()) else it })
    }

    fun moveToTop(id: String): AppConfig {
        val profile = profiles.firstOrNull { it.id == id } ?: error("配置不存在")
        return copy(profiles = listOf(profile) + profiles.filterNot { it.id == id })
    }

    fun toggleFavorite(id: String): AppConfig {
        val profile = profiles.firstOrNull { it.id == id } ?: error("配置不存在")
        return upsert(profile.copy(favorite = !profile.favorite))
    }

    companion object {
        const val MAX_PROFILES = 50
        const val MAX_CATEGORY_NAME = 60
        val EMPTY = AppConfig(null, emptyList())
        // Fallback for platform-independent decoding; new profiles use the device's mobile UA.
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun normalizeCategoryName(value: String): String {
            val name = value.trim()
            require(name.length <= MAX_CATEGORY_NAME && name.none { Character.isISOControl(it) }) {
                "分类名称最多 60 个字符，不能包含控制字符"
            }
            return name
        }

        fun normalizeUserAgent(value: String): String {
            val text = value.trim()
            require(text.isNotBlank() && text.length <= 2048 && value.all { it.code in 32..126 }) {
                "UA 须为 1–2048 个可打印 ASCII 字符，不能包含换行"
            }
            return text
        }

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
            val normalized = uri.toASCIIString()
            require(normalized.length <= 8192) { "网址过长，请缩短路径或参数后重试" }
            return normalized
        }

        fun normalizeOptionalUrl(value: String): String {
            val text = value.trim()
            return if (text.isEmpty()) "" else normalizeUrl(text)
        }
    }
}

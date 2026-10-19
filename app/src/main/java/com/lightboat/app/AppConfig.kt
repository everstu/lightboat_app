package com.lightboat.app

import java.net.URI
import java.util.UUID

data class SharedScript(val id: String, val name: String, val url: String)
data class ProfileScript(val scriptId: String, val enabled: Boolean = true, val legacyStorage: Boolean = false)

data class Profile(
    val id: String,
    val name: String,
    val url: String,
    val scripts: List<ProfileScript> = emptyList(),
    val userAgent: String = AppConfig.DEFAULT_USER_AGENT,
    val platform: BrowserPlatform = MobileUserAgent.detect(userAgent),
    val categoryId: String? = null,
    val favorite: Boolean = false
)

data class AppConfig(
    val lastProfileId: String?,
    val profiles: List<Profile>,
    val categories: Map<String, String> = emptyMap(),
    val scriptLibrary: List<SharedScript> = emptyList()
) {
    val lastProfile: Profile? get() = profiles.firstOrNull { it.id == lastProfileId }

    fun upsert(profile: Profile): AppConfig {
        require(profile.categoryId == null || categories.containsKey(profile.categoryId)) { "配置引用的分类不存在" }
        require(profile.scripts.size <= MAX_PROFILE_SCRIPTS) { "每组配置最多选择 $MAX_PROFILE_SCRIPTS 个脚本" }
        require(profile.scripts.map { it.scriptId }.distinct().size == profile.scripts.size) { "同一配置不能重复引用脚本" }
        require(profile.scripts.all { ref -> scriptLibrary.any { it.id == ref.scriptId } }) { "配置引用的脚本不存在" }
        require(profile.scripts.count { it.legacyStorage } <= 1) { "配置只能有一个旧版脚本存储" }
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

    fun renameCategory(id: String, value: String): AppConfig {
        require(categories.containsKey(id)) { "分类不存在" }
        val name = normalizeCategoryName(value)
        require(name.isNotEmpty()) { "请填写分类名称" }
        require(categories.none { (otherId, otherName) -> otherId != id && otherName.equals(name, ignoreCase = true) }) {
            "已有同名分类，请使用其他名称"
        }
        return copy(categories = categories + (id to name))
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

    fun activeScripts(profile: Profile): List<SharedScript> = profile.scripts.filter { it.enabled }.map { ref ->
        scriptLibrary.firstOrNull { it.id == ref.scriptId } ?: error("配置引用的脚本不存在")
    }

    fun upsertScript(script: SharedScript): AppConfig {
        require(script.id.isNotBlank() && script.id.length <= 100) { "脚本 ID 无效" }
        val url = normalizeScriptUrl(script.url)
        val normalized = script.copy(name = normalizeScriptName(script.name, url), url = url)
        require(scriptLibrary.none { it.id != script.id && it.url == normalized.url }) { "这个地址已在脚本库中，请直接选择已有脚本" }
        val next = if (scriptLibrary.any { it.id == script.id }) scriptLibrary.map { if (it.id == script.id) normalized else it }
            else scriptLibrary + normalized
        require(next.size <= MAX_LIBRARY_SCRIPTS) { "脚本库最多保存 $MAX_LIBRARY_SCRIPTS 个脚本" }
        return copy(scriptLibrary = next)
    }

    fun removeScript(id: String): AppConfig = copy(scriptLibrary = scriptLibrary.filterNot { it.id == id },
        profiles = profiles.map { it.copy(scripts = it.scripts.filterNot { ref -> ref.scriptId == id }) })

    companion object {
        const val MAX_PROFILES = 50
        const val MAX_CATEGORY_NAME = 60
        const val MAX_PROFILE_SCRIPTS = 16
        const val MAX_LIBRARY_SCRIPTS = 100
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

        fun normalizeScriptName(value: String, url: String): String {
            val name = value.trim()
            require(name.length <= 60 && name.none { Character.isISOControl(it) }) {
                "脚本名称最多 60 个字符，不能包含控制字符"
            }
            return name.ifEmpty { defaultScriptName(url) }
        }

        fun defaultScriptName(url: String): String {
            val filename = URI(url).path.orEmpty().substringAfterLast('/')
                .filterNot { Character.isISOControl(it) }.trim()
            val end = minOf(60, filename.length).let {
                if (it < filename.length && it > 0 && filename[it - 1].isHighSurrogate()) it - 1 else it
            }
            return filename.take(end).ifBlank { "脚本" }
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

        fun normalizeScriptUrl(value: String): String {
            val uri = URI(normalizeUrl(value))
            val scheme = uri.scheme.lowercase()
            val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
            return "$scheme://${uri.host.lowercase()}${port?.let { ":$it" }.orEmpty()}" + uri.rawPath.orEmpty().ifBlank { "/" } +
                (uri.rawQuery?.let { "?$it" }.orEmpty()) + (uri.rawFragment?.let { "#$it" }.orEmpty())
        }
    }
}

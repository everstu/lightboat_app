package com.lightboat.app.userscript

import android.util.Base64
import com.lightboat.app.AppConfig
import com.lightboat.app.BrowserScriptLoader
import com.lightboat.app.BrowserPlatform
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

class ScriptPreparer(private val loader: BrowserScriptLoader) {
    fun prepare(source: String, address: String, userAgent: String, platform: BrowserPlatform,
        callback: (Result<ScriptBundle>) -> Unit) {
        val meta = try { ScriptMetadata.parse(source) } catch (error: Exception) { callback(Result.failure(error)); return }
        val requirements = mutableListOf<String>()
        val resources = JSONObject()
        val jobs = meta.all("require").map { null to it } + meta.all("resource").map { directive ->
            val split = directive.split(Regex("\\s+"), limit = 2)
            if (split.size != 2) { callback(Result.failure(IllegalArgumentException("@resource 需要名称和 URL"))); return }
            split[0] to split[1]
        }
        fun next(index: Int, totalBytes: Int) {
            if (index == jobs.size) { callback(Result.success(ScriptBundle(source, meta, requirements, resources))); return }
            val (name, reference) = jobs[index]
            val full = try { AppConfig.normalizeUrl(URI(address).resolve(reference).toString()) } catch (error: Exception) {
                callback(Result.failure(error)); return
            }
            loader.loadAsset(full.substringBefore('#'), userAgent, platform, name != null) { result ->
                result.fold(onSuccess = { asset ->
                    try {
                        val bytes = Base64.decode(asset.getString("base64"), Base64.DEFAULT)
                        val checksum = URI(full).rawFragment
                        if (!checksum.isNullOrEmpty()) {
                            val integrity = Regex("^(sha256|sha384|sha512)[=-](.+)$").matchEntire(checksum)
                                ?: error("不支持依赖完整性格式：$checksum")
                            val algorithm = integrity.groupValues[1].replace("sha", "SHA-")
                            val actual = MessageDigest.getInstance(algorithm).digest(bytes)
                            val expected = integrity.groupValues[2]
                            val hex = actual.joinToString("") { "%02x".format(it.toInt() and 255) }
                            require(hex.equals(expected, true) || Base64.encodeToString(actual, Base64.NO_WRAP) == expected) {
                                "依赖完整性校验失败：$full"
                            }
                        }
                        require(totalBytes + bytes.size <= 8 * 1024 * 1024) { "依赖资源总大小不能超过 8 MiB" }
                        if (name == null) requirements.add(asset.getString("text")) else resources.put(name, asset)
                        next(index + 1, totalBytes + bytes.size)
                    } catch (error: Exception) { callback(Result.failure(error)) }
                }, onFailure = { callback(Result.failure(it)) })
            }
        }
        next(0, 0)
    }
}

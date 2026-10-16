package com.lightboat.app

import okhttp3.Call
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object ConfigImportReader {
    const val MAX_BYTES = 1_048_576

    fun fromUrl(call: Call, defaultUserAgent: String): AppConfig = call.execute().use { response ->
        require(response.isSuccessful) { "链接返回 HTTP ${response.code}，请确认配置文件可以直接访问。" }
        val body = response.body ?: error("链接没有返回配置内容。")
        require(body.contentLength() <= MAX_BYTES) { "配置文件不能超过 1 MiB。" }
        body.byteStream().use { fromStream(it, defaultUserAgent) }
    }

    fun fromStream(input: InputStream, defaultUserAgent: String): AppConfig {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "配置文件不能超过 1 MiB。" }
            output.write(buffer, 0, count)
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString().removePrefix("\uFEFF").trim()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("配置文件编码无法识别，请使用 UTF-8 编码的 JSON 文件。")
        }
        require(text.isNotEmpty()) { "配置文件为空，请选择包含配置的 JSON 文件。" }
        require(text.startsWith('{')) { "内容不是配置 JSON，请使用可直接下载的配置链接或文件。" }
        return try {
            ConfigCodec.decode(text, defaultUserAgent)
        } catch (_: JSONException) {
            throw IllegalArgumentException("配置格式不正确，请使用轻舟导出的 JSON 文件。")
        }
    }
}

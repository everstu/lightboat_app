package com.lightboat.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ConfigImportReaderTest {
    private val userAgent = "Test Android Mobile/1.0"
    private val config = AppConfig.EMPTY.upsert(Profile("first", "工作", "https://example.com/", "https://example.com/app.js"))
    private val json get() = ConfigCodec.encode(config)

    @Test fun localUtf8AndBomPreserveConfigurationOrderAndSelection() {
        val second = Profile("second", "第二组", "https://example.org/", "https://example.org/app.js")
        val ordered = config.upsert(second, "工作").toggleFavorite(second.id)
            .reorder(listOf(second.id, "first")).select(second.id)
        val bytes = ("\uFEFF" + ConfigCodec.encode(ordered)).toByteArray(Charsets.UTF_8)
        assertEquals(ordered, ConfigImportReader.fromStream(ByteArrayInputStream(bytes), userAgent))
    }

    @Test fun bothSourcesRejectHtmlBadSchemaAndInvalidUtf8() {
        listOf("", "<html>login required</html>", "{}", "[1,2,3]", json.replace("\"first\"", "\"missing\"", false)
            .replaceFirst("\"id\": \"missing\"", "\"id\": \"first\"")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ConfigImportReader.fromStream(invalid.byteInputStream(), userAgent)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigImportReader.fromStream(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), userAgent)
        }
    }

    @Test fun sizeLimitMeasuresBytesIncludingMultibyteText() {
        val bytes = "中".repeat(ConfigImportReader.MAX_BYTES / 3 + 1).toByteArray(Charsets.UTF_8)
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigImportReader.fromStream(ByteArrayInputStream(bytes), userAgent)
        }
        assertTrue(error.message.orEmpty().contains("1 MiB"))
    }

    @Test fun urlFollowsRedirectsAndReadsJsonServedAsPlainText() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual.json"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody(json))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/share")).build())
            assertEquals(config, ConfigImportReader.fromUrl(call, userAgent))
            assertEquals("/share", server.takeRequest().path)
            assertEquals("/actual.json", server.takeRequest().path)
        }
    }

    @Test fun urlRejectsHttpErrorsAndHtmlEvenWhenStatusIsOk() {
        MockWebServer().use { server ->
            listOf(MockResponse().setResponseCode(403).setBody(json),
                MockResponse().setHeader("Content-Type", "text/html").setBody("<html>not a config</html>")).forEach { response ->
                server.enqueue(response)
                val call = OkHttpClient().newCall(Request.Builder().url(server.url("/config")).build())
                assertThrows(IllegalArgumentException::class.java) { ConfigImportReader.fromUrl(call, userAgent) }
            }
        }
    }

    @Test fun unknownLengthResponseStillHasABoundedRead() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(ConfigImportReader.MAX_BYTES + 1) { 32 }), 8192))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/config")).build())
            val error = assertThrows(IllegalArgumentException::class.java) { ConfigImportReader.fromUrl(call, userAgent) }
            assertTrue(error.message.orEmpty().contains("1 MiB"))
        }
    }
}

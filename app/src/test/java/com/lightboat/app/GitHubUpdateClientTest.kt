package com.lightboat.app

import com.lightboat.app.update.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class GitHubUpdateClientTest {
    private val server = MockWebServer()
    private lateinit var client: GitHubUpdateClient
    private val directory = Files.createTempDirectory("lightboat-update-test").toFile()

    @Before fun setUp() { server.start(); client = GitHubUpdateClient(server.url("/latest")) }
    @After fun tearDown() { client.close(); server.shutdown(); directory.deleteRecursively() }

    @Test fun numericVersionsCompareComponentsAndRejectNonStableTags() {
        assertTrue(version("v1.10.0") > version("1.9.9"))
        assertEquals(0, version("1.2").compareTo(version("1.2.0")))
        assertTrue(version("1.0.4") > version("v1.0.3"))
        for (tag in listOf("1.0.4-beta", "latest", "1", "1.-2.3", "1.2.3.4.5", "1.999999999999999999999")) {
            assertNull(tag, ReleaseVersion.parse(tag))
        }
    }

    @Test fun latestUsesGitHubHeadersAndParsesNotesAndApk() {
        server.enqueue(MockResponse().setBody(release().toString()))
        val result = client.latest()
        assertEquals("v1.0.4", result.tag)
        assertEquals("修复与优化", result.notes)
        assertEquals("lightboat-v1.0.4-debug.apk", result.apk!!.name)
        val request = server.takeRequest()
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun noReleaseAndRateLimitsHaveDistinctErrors() {
        for ((code, failure) in listOf(404 to UpdateFailure.NO_RELEASE, 403 to UpdateFailure.RATE_LIMIT,
            429 to UpdateFailure.RATE_LIMIT, 500 to UpdateFailure.NETWORK)) {
            server.enqueue(MockResponse().setResponseCode(code))
            failure(failure) { client.latest() }
        }
    }

    @Test fun draftsPrereleasesAndInvalidResponsesAreRejected() {
        for (json in listOf(release().put("draft", true), release().put("prerelease", true), release().put("tag_name", "next"))) {
            failure(UpdateFailure.INVALID_RELEASE) { GitHubUpdateClient.parseRelease(json.toString()) }
        }
        failure(UpdateFailure.INVALID_RELEASE) { GitHubUpdateClient.parseRelease("<html>Error</html>") }
        server.enqueue(MockResponse().setChunkedBody(" ".repeat(1024 * 1024 + 1), 8192))
        failure(UpdateFailure.INVALID_RELEASE) { client.latest() }
    }

    @Test fun attachmentSelectionUsesPublishedNameAndDoesNotGuessBetweenOtherApks() {
        val expected = asset()
        val extra = asset().put("name", "other.apk")
        assertEquals(expected.getString("name"), GitHubUpdateClient.parseRelease(
            release().put("assets", JSONArray().put(extra).put(expected)).toString()).apk!!.name)
        assertNull(GitHubUpdateClient.parseRelease(release().put("assets", JSONArray()).toString()).apk)
        failure(UpdateFailure.AMBIGUOUS_APK) {
            GitHubUpdateClient.parseRelease(release().put("assets", JSONArray().put(extra).put(asset().put("name", "another.apk"))).toString())
        }
    }

    @Test fun unsafeAttachmentUrlsAndInvalidSizesOrDigestsAreRejected() {
        for (url in listOf("http://github.com/everstu/lightboat_app/releases/download/v1/a.apk",
            "https://example.com/a.apk", "https://github.com/other/repo/releases/download/v1/a.apk",
            "https://user:secret@github.com/everstu/lightboat_app/releases/download/v1/a.apk")) {
            failure(UpdateFailure.INVALID_RELEASE) {
                GitHubUpdateClient.parseRelease(release(asset().put("browser_download_url", url)).toString())
            }
        }
        for (size in listOf(0, GitHubUpdateClient.MAX_APK_BYTES + 1)) {
            failure(UpdateFailure.INVALID_RELEASE) { GitHubUpdateClient.parseRelease(release(asset().put("size", size)).toString()) }
        }
        failure(UpdateFailure.INVALID_RELEASE) { GitHubUpdateClient.parseRelease(release(asset().put("digest", "sha256:invalid")).toString()) }
    }

    @Test fun downloadStreamsExactBytesAndChecksDigest() {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 4096))
        val destination = File(directory, "update.apk")
        var done = 0L
        client.download(ReleaseApk("test.apk", server.url("/asset"), bytes.size.toLong(), digest), destination) { count, size ->
            assertEquals(bytes.size.toLong(), size); done = count
        }
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(bytes.size.toLong(), done)
    }

    @Test fun truncatedOversizedAndCorruptedDownloadsDeletePartialFile() {
        val cases = listOf(MockResponse().setBody("short"), MockResponse().setChunkedBody("longer-than-declared", 2),
            MockResponse().setBody("12345678"), MockResponse().setResponseCode(404))
        for (response in cases) {
            server.enqueue(response)
            val destination = File(directory, "update.apk")
            failure(UpdateFailure.DOWNLOAD) {
                client.download(ReleaseApk("test.apk", server.url("/asset"), 8, "0".repeat(64)), destination) { _, _ -> }
            }
            assertFalse(destination.exists())
        }
    }

    private fun version(value: String) = ReleaseVersion.parse(value)!!
    private fun failure(expected: UpdateFailure, work: () -> Unit) {
        try { work(); fail("Expected $expected") } catch (error: UpdateException) { assertEquals(expected, error.reason) }
    }
    private fun asset() = JSONObject().put("name", "lightboat-v1.0.4-debug.apk").put("size", 1024)
        .put("browser_download_url", "https://github.com/everstu/lightboat_app/releases/download/v1.0.4/lightboat-v1.0.4-debug.apk")
    private fun release(asset: JSONObject = asset()) = JSONObject().put("draft", false).put("prerelease", false)
        .put("tag_name", "v1.0.4").put("body", "修复与优化").put("assets", JSONArray().put(asset))
}

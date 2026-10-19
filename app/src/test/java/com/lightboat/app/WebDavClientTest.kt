package com.lightboat.app

import com.lightboat.app.backup.RemoteBackup
import com.lightboat.app.backup.WebDavClient
import com.lightboat.app.backup.WebDavConflict
import com.lightboat.app.backup.WebDavSettings
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun settings(server: MockWebServer) = WebDavSettings.normalized(server.url("/dav/").toString(), "account", "private-password")
    private fun collection() = MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>""")

    @Test fun uploadCreatesItsOwnSubdirectoryInsideTheConfiguredParent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201).addHeader("ETag", "\"new\""))
            val file = temporary.newFile().apply { writeText("backup") }
            WebDavClient(settings(server)).use { it.upload(file, RemoteBackup(false, null, null, null)) }
            val check = server.takeRequest(); assertEquals("PROPFIND", check.method); assertEquals("/dav/lightboat/", check.path)
            val mkdir = server.takeRequest(); assertEquals("MKCOL", mkdir.method); assertEquals("/dav/lightboat/", mkdir.path)
            val upload = server.takeRequest(); assertEquals("PUT", upload.method); assertEquals("/dav/lightboat/lightboat-backup.zip", upload.path)
            assertEquals("*", upload.getHeader("If-None-Match"))
        }
    }

    @Test fun directoryConnectionTestUsesWebdavAndBasicAuthWithoutCreatingRemoteData() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>"""))
            server.enqueue(MockResponse().setResponseCode(404))
            WebDavClient(settings(server)).use { assertFalse(it.testConnection().exists) }
            val probe = server.takeRequest()
            assertEquals("PROPFIND", probe.method); assertEquals("/dav/", probe.path)
            assertEquals("0", probe.getHeader("Depth"))
            assertEquals(Credentials.basic("account", "private-password", Charsets.UTF_8), probe.getHeader("Authorization"))
            val head = server.takeRequest(); assertEquals("HEAD", head.method); assertEquals("/dav/lightboat/lightboat-backup.zip", head.path)
        }
    }

    @Test fun uploadsUseConditionalCreateOrExpectedVersionAndConflictsNeverRetryUnconditionally() {
        MockWebServer().use { server ->
            val file = temporary.newFile().apply { writeText("zip bytes") }
            server.enqueue(collection())
            server.enqueue(MockResponse().setResponseCode(201).addHeader("ETag", "\"one\""))
            server.enqueue(collection())
            server.enqueue(MockResponse().setResponseCode(204).addHeader("ETag", "\"two\""))
            server.enqueue(collection())
            server.enqueue(MockResponse().setResponseCode(412))
            WebDavClient(settings(server)).use { client ->
                val first = client.upload(file, RemoteBackup(false, null, null, null))
                assertEquals("\"one\"", first.etag)
                assertEquals("\"two\"", client.upload(file, first).etag)
                assertThrows(WebDavConflict::class.java) { client.upload(file, first) }
            }
            assertEquals("PROPFIND", server.takeRequest().method)
            val first = server.takeRequest(); assertEquals("PUT", first.method); assertEquals("*", first.getHeader("If-None-Match"))
            assertEquals("zip bytes", first.body.readUtf8())
            assertEquals("PROPFIND", server.takeRequest().method)
            val second = server.takeRequest(); assertEquals("\"one\"", second.getHeader("If-Match"))
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("\"one\"", server.takeRequest().getHeader("If-Match"))
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun doesNotOverwriteAnExistingBackupWithoutAStrongVersionIdentifier() {
        MockWebServer().use { server ->
            val file = temporary.newFile().apply { writeText("zip") }
            WebDavClient(settings(server)).use { client ->
                for (tag in listOf(null, "W/\"weak\"")) assertThrows(IllegalArgumentException::class.java) {
                    client.upload(file, RemoteBackup(true, tag, null, null))
                }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun unknownLengthDownloadsEnforceActualByteLimitAndRemovePartialFiles() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("x".repeat(1025), 32))
            val file = temporary.newFile()
            WebDavClient(settings(server), maximumBytes = 1024).use { client ->
                assertThrows(IllegalArgumentException::class.java) { client.download(file) }
            }
            assertFalse(file.exists())
        }
    }

    @Test fun downloadsPreserveRawZipAndVersionAndAuthenticationErrorsDoNotExposeResponseBodies() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"remote\"").setBody("raw backup bytes"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-password"))
            WebDavClient(settings(server)).use { client ->
                val file = temporary.newFile()
                assertEquals("\"remote\"", client.download(file).etag)
                assertEquals("raw backup bytes", file.readText())
                val error = assertThrows(IllegalStateException::class.java) { client.inspect() }
                assertTrue(error.message!!.contains("401")); assertFalse(error.message!!.contains("private-password"))
            }
        }
    }

    @Test fun credentialedRedirectsAreNotFollowedAndDirectoryUrlsAreNormalized() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://other.example/"))
            WebDavClient(settings(server)).use { client -> assertThrows(IllegalStateException::class.java) { client.inspect() } }
            assertEquals(1, server.requestCount)
            val normalized = WebDavSettings.normalized(server.url("/dav/folder").toString(), "", "")
            assertEquals("/dav/folder/lightboat/lightboat-backup.zip", normalized.backupUrl.encodedPath)
            for (url in listOf("https://user:password@example.com/dav/", "https://example.com/dav/?token=1", "https://example.com/dav/#fragment"))
                assertThrows(IllegalArgumentException::class.java) { WebDavSettings.normalized(url, "", "") }
        }
    }

    @Test fun directoryCreationRaceIsRecheckedAndPermissionFailureNeverUploads() {
        MockWebServer().use { server ->
            val file = temporary.newFile().apply { writeText("backup") }
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(collection())
            server.enqueue(MockResponse().setResponseCode(201))
            WebDavClient(settings(server)).use { it.upload(file, RemoteBackup(false, null, null, null)) }
            assertEquals(listOf("PROPFIND", "MKCOL", "PROPFIND", "PUT"), (1..4).map { server.takeRequest().method })
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(403))
            WebDavClient(settings(server)).use { client ->
                assertThrows(IllegalStateException::class.java) { client.upload(file, RemoteBackup(false, null, null, null)) }
            }
            assertEquals("PROPFIND", server.takeRequest().method); assertEquals("MKCOL", server.takeRequest().method)
            assertEquals(6, server.requestCount)
        }
    }
}

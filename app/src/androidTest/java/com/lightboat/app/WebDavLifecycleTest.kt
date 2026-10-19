package com.lightboat.app

import android.os.StrictMode
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.backup.WebDavClient
import com.lightboat.app.backup.WebDavSettings
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WebDavLifecycleTest {
    @Test fun closingAnIdleHttpsConnectionOnTheUiThreadNeverPerformsNetworkIo() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(404))
            val pool = ConnectionPool()
            val http = OkHttpClient.Builder().connectionPool(pool).protocols(listOf(Protocol.HTTP_1_1))
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            WebDavClient(WebDavSettings.normalized(server.url("/dav/").toString(), "", ""), http).use { client ->
                assertFalse(client.inspect().exists)
                assertEquals("The regression needs an idle live TLS socket", 1, pool.idleConnectionCount())
                val failure = AtomicReference<Throwable?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val policy = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(policy).detectNetwork().penaltyDeathOnNetwork().build())
                    try { client.close(); client.close() }
                    catch (error: Throwable) { failure.set(error) }
                    finally { StrictMode.setThreadPolicy(policy) }
                }
                assertNull("Closing WebDAV on the UI thread performed TLS network I/O: ${failure.get()}", failure.get())
                assertThrows(IllegalStateException::class.java) { client.inspect() }
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (pool.connectionCount() != 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertEquals("Closing must eventually release TLS connections", 0, pool.connectionCount())
            }
        }
    }
}

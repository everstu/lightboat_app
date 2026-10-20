package com.lightboat.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class MediaBoundaryTest {
    @Test fun crossOriginMediaOnlyExposesTheCorsAccessGrantedByItsUpstream() {
        MockWebServer().use { server ->
            val network = MediaRequestClient("Test", { "session=private" }, addresses = MediaAddressPolicy(allowPrivateForTests = true))
            val proxy = MediaRequestProxy(network, allowLoopbackUpstreamForTests = true)
            proxy.start()
            try {
                val browser = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
                fun request(origin: String, response: MockResponse, anonymous: Boolean = false): okhttp3.Response {
                    server.enqueue(response.setBody("media").setHeader("Content-Type", "video/mp4"))
                    return browser.newCall(Request.Builder().url(proxy.url(server.url("/movie").toString(), origin, if (anonymous) origin else null))
                        .header("Origin", origin).build()).execute()
                }
                request("https://page.example", MockResponse()).use {
                    assertEquals(200, it.code); assertNull(it.header("Access-Control-Allow-Origin"))
                    assertNull(it.header("Access-Control-Allow-Credentials")); assertNull(it.header("Access-Control-Allow-Private-Network"))
                }
                request("https://page.example", MockResponse().setHeader("Access-Control-Allow-Origin", "https://page.example")
                    .setHeader("Access-Control-Allow-Credentials", "true")).use {
                    assertEquals("https://page.example", it.header("Access-Control-Allow-Origin"))
                    assertEquals("true", it.header("Access-Control-Allow-Credentials"))
                }
                request("https://page.example", MockResponse().setHeader("Access-Control-Allow-Origin", "*")
                    .setHeader("Access-Control-Allow-Credentials", "true")).use {
                    assertNull(it.header("Access-Control-Allow-Origin")); assertNull(it.header("Access-Control-Allow-Credentials"))
                }
                request("https://page.example", MockResponse().setHeader("Access-Control-Allow-Origin", "https://page.example")).use {
                    assertNull(it.header("Access-Control-Allow-Origin"))
                }
                request("https://page.example", MockResponse().setHeader("Access-Control-Allow-Origin", "*"), anonymous = true).use {
                    assertEquals("*", it.header("Access-Control-Allow-Origin")); assertNull(it.header("Access-Control-Allow-Credentials"))
                }
                request(server.url("/").toString().removeSuffix("/"), MockResponse()).use {
                    assertEquals(server.url("/").toString().removeSuffix("/"), it.header("Access-Control-Allow-Origin"))
                }
                repeat(6) { index -> assertEquals(if (index == 4) null else "session=private", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Cookie")) }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>private</html>"))
                browser.newCall(Request.Builder().url(proxy.url(server.url("/account").toString(), "https://page.example"))
                    .header("Origin", "https://page.example").build()).execute().use {
                    assertEquals(502, it.code); assertFalse(it.body!!.string().contains("private"))
                    assertNull(it.header("Access-Control-Allow-Origin"))
                }
            } finally { proxy.close(); network.close() }
        }
    }

    @Test fun privateAddressesAreRejectedIncludingDnsAnswersAndRedirects() {
        for (host in listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "fe80::1", "::192.168.1.1", "64:ff9b::192.168.1.1")) {
            assertFalse(host, MediaAddressPolicy.isPublic(InetAddress.getByName(host)))
        }
        val dnsPolicy = MediaAddressPolicy(resolver = object : okhttp3.Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        })
        try { dnsPolicy.dns.lookup("cdn.example"); fail("Private DNS answer accepted") } catch (_: UnknownHostException) { }
        MockWebServer().use { first -> MockWebServer().use { second ->
            val initial = first.url("/start").newBuilder().host("127.0.0.1").build()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/private").newBuilder().host("localhost").build()))
            val network = MediaRequestClient("Test", addresses = MediaAddressPolicy(initial.toString()))
            try {
                try { network.execute(initial.toString(), "GET", emptyMap()).close(); fail("Private redirect accepted") }
                catch (_: UnknownHostException) { }
                assertEquals(1, first.requestCount); assertEquals(0, second.requestCount)
            } finally { network.close() }
        } }
    }
}

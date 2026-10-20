package com.lightboat.app

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/** Public sites cannot turn the media route into a private-network request client. */
internal class MediaAddressPolicy(
    pageUrl: String? = null,
    private val resolver: Dns = Dns.SYSTEM,
    private val allowPrivateForTests: Boolean = false,
) {
    private val configured = pageUrl?.toHttpUrlOrNull()
    // Only an explicitly configured private IP may opt into LAN media. A public
    // hostname resolving to a private IP must still fail (including DNS rebinding).
    private val privateHost = configured?.host?.takeIf { host ->
        (':' in host || host.all { it.isDigit() || it == '.' }) &&
            runCatching { !isPublic(InetAddress.getByName(host)) }.getOrDefault(false)
    }

    fun check(url: HttpUrl) {
        require(url.username.isEmpty() && url.password.isEmpty()) { "Media URL credentials are not supported" }
        // OkHttp bypasses Dns for IP literals; check those before opening a socket.
        if (':' in url.host || url.host.all { it.isDigit() || it == '.' }) {
            checkAddresses(url.host, listOf(InetAddress.getByName(url.host)))
        }
        if (!allowPrivateForTests && privateHost == url.host) {
            require(configured?.scheme == url.scheme && configured.port == url.port) { "Private media origin changed" }
        }
    }

    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolver.lookup(hostname).also { checkAddresses(hostname, it) }
    }

    private fun checkAddresses(host: String, addresses: List<InetAddress>) {
        if (addresses.isEmpty()) throw UnknownHostException("No media upstream address")
        if (allowPrivateForTests || privateHost == host) return
        if (addresses.any { !isPublic(it) }) throw UnknownHostException("Private media upstream is not allowed")
    }

    companion object {
        internal fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            val first = bytes[0].toInt() and 255
            if (bytes.size == 16) {
                if (first and 0xfe == 0xfc || bytes.take(12).all { it == 0.toByte() }) return false
                // Standard NAT64/IPv4-translated destinations must not encode a
                // private IPv4 target inside an otherwise routable IPv6 address.
                val nat64 = bytes.take(12) == listOf(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0).map(Int::toByte)
                val translated = bytes.take(12) == listOf(0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0, 0).map(Int::toByte)
                return !(nat64 || translated) || isPublic(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            }
            val second = bytes[1].toInt() and 255
            return first != 0 && first < 224 && !(first == 100 && second in 64..127) &&
                !(first == 198 && second in 18..19) && !(first == 192 && second == 0)
        }
    }
}

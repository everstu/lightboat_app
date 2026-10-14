package com.lightboat.app

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata

object MobileUserAgent {
    fun default(context: Context): String = WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace(" Version/4.0", "")

    fun windowsChrome(androidUserAgent: String): String {
        val version = chromeVersion(androidUserAgent)
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    fun isWindowsChrome(userAgent: String) = "Windows NT" in userAgent && "Chrome/" in userAgent

    fun windowsMetadata(userAgent: String): UserAgentMetadata {
        val version = chromeVersion(userAgent)
        val major = version.substringBefore('.')
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium")
                .setMajorVersion(major).setFullVersion(version).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome")
                .setMajorVersion(major).setFullVersion(version).build(),
        )
        return UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(version)
            .setPlatform("Windows")
            .setPlatformVersion("10.0.0")
            .setArchitecture("x86")
            .setModel("")
            .setMobile(false)
            .setBitness(64)
            .setWow64(false)
            .build()
    }

    private fun chromeVersion(userAgent: String) =
        Regex("Chrome/([0-9.]+)").find(userAgent)?.groupValues?.get(1) ?: "131.0.0.0"
}

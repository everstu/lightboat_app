package com.lightboat.app

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata

enum class BrowserPlatform(val configValue: String) {
    APPLE("apple"), ANDROID("android"), WINDOWS("windows");

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.configValue == value.lowercase() }
            ?: throw IllegalArgumentException("不支持的平台：$value")
    }
}

object MobileUserAgent {
    fun default(context: Context): String = WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace(" Version/4.0", "")

    fun windowsChrome(androidUserAgent: String): String {
        val version = chromeVersion(androidUserAgent)
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    fun appleSafari(): String = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"

    fun detect(userAgent: String): BrowserPlatform = when {
        "iPhone" in userAgent || "iPad" in userAgent -> BrowserPlatform.APPLE
        "Windows NT" in userAgent -> BrowserPlatform.WINDOWS
        else -> BrowserPlatform.ANDROID
    }

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

    fun appleMetadata(): UserAgentMetadata {
        val safari = UserAgentMetadata.BrandVersion.Builder().setBrand("Safari")
            .setMajorVersion("18").setFullVersion("18.5").build()
        return UserAgentMetadata.Builder()
            .setBrandVersionList(listOf(safari))
            .setFullVersion("18.5")
            .setPlatform("iOS")
            .setPlatformVersion("18.5.0")
            .setArchitecture("")
            .setModel("iPhone")
            .setMobile(true)
            .setBitness(64)
            .setWow64(false)
            .build()
    }

    fun metadata(platform: BrowserPlatform, userAgent: String, android: UserAgentMetadata?): UserAgentMetadata? =
        when (platform) {
            BrowserPlatform.APPLE -> appleMetadata()
            BrowserPlatform.ANDROID -> android
            BrowserPlatform.WINDOWS -> windowsMetadata(userAgent)
        }

    fun navigatorIdentityScript(platform: BrowserPlatform, userAgent: String): String? {
        val (values, userAgentData) = when (platform) {
            BrowserPlatform.APPLE -> "{platform:'iPhone',vendor:'Apple Computer, Inc.',maxTouchPoints:5}" to "undefined"
            BrowserPlatform.ANDROID -> return null
            BrowserPlatform.WINDOWS -> {
                val version = chromeVersion(userAgent)
                val major = version.substringBefore('.')
                val brands = "[{brand:'Chromium',version:'$major'},{brand:'Google Chrome',version:'$major'}]"
                val fullVersions = "[{brand:'Chromium',version:'$version'},{brand:'Google Chrome',version:'$version'}]"
                "{platform:'Win32',vendor:'Google Inc.',maxTouchPoints:0}" to
                    "{brands:$brands,mobile:false,platform:'Windows',toJSON(){return this;}," +
                    "getHighEntropyValues(){return Promise.resolve({...this,architecture:'x86',bitness:'64'," +
                    "model:'',platformVersion:'10.0.0',uaFullVersion:'$version',fullVersionList:$fullVersions,wow64:false});}}"
            }
        }
        return """(() => {
          const values = $values;
          for (const [name, value] of Object.entries(values)) {
            try { Object.defineProperty(Navigator.prototype, name, { get: () => value, configurable: true }); } catch (_) {}
          }
          try { Object.defineProperty(Navigator.prototype, 'userAgentData', { get: () => $userAgentData, configurable: true }); } catch (_) {}
        })();
        //# sourceURL=lightboat-platform-identity.js"""
    }

    private fun chromeVersion(userAgent: String) =
        Regex("Chrome/([0-9.]+)").find(userAgent)?.groupValues?.get(1) ?: "131.0.0.0"
}

package com.lightboat.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URI

/** Fetches text through a short-lived Chromium WebView, without executing downloaded code. */
class BrowserScriptLoader(
    private val context: Context,
    private val container: ViewGroup
) {
    private var view: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var requestSerial = 0L
    private var activeRequest = 0L
    private var systemUserAgentMetadata: UserAgentMetadata? = null
    private val fetchCode by lazy(LazyThreadSafetyMode.NONE) {
        context.assets.open("fetch-script.js").bufferedReader().use { it.readText() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun load(url: String, userAgent: String, platform: BrowserPlatform, callback: (Result<String>) -> Unit) {
        loadInternal(url, userAgent, platform, "script") { result -> callback(result.map { it.getString("script") }) }
    }

    fun loadAsset(url: String, userAgent: String, platform: BrowserPlatform, resource: Boolean,
        callback: (Result<JSONObject>) -> Unit) {
        loadInternal(url, userAgent, platform, if (resource) "resource" else "require", callback)
    }

    @SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
    private fun loadInternal(url: String, userAgent: String, platform: BrowserPlatform, mode: String,
        callback: (Result<JSONObject>) -> Unit) {
        DebugLog.event("script-loader", "start mode=$mode url=${DebugLog.safeUrl(url)}")
        // Like v1.0.0, each download gets its own short-lived WebView.
        close()
        val parsed = URI(url)
        val origin = URI(parsed.scheme.lowercase(), null, parsed.host.lowercase(), parsed.port, null, null, null).toString()
        val loader = WebView(context).also {
            view = it
            it.alpha = 0f
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            container.addView(it, ViewGroup.LayoutParams(1, 1))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            systemUserAgentMetadata = WebSettingsCompat.getUserAgentMetadata(loader.settings)
        }
        loader.settings.apply {
            userAgentString = userAgent
            javaScriptEnabled = true
            // Downloaded scripts must be fresh even though browsing uses
            // Chromium's normal cache policy.
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkImage = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(loader.settings, emptySet())
        }
        applyUserAgentMetadata(loader, userAgent, platform)
        val request = ++requestSerial
        activeRequest = request
        fun finish(result: Result<JSONObject>) {
            if (view !== loader || activeRequest != request) return
            DebugLog.event("script-loader", "finish mode=$mode ok=${result.isSuccess} url=${DebugLog.safeUrl(url)} error=${result.exceptionOrNull()?.message.orEmpty().take(180)}")
            // Dispose this request before callbacks can start a new dependency
            // download. Otherwise renderer-exit cleanup can cancel the new view.
            close()
            callback(result)
        }
        loader.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                DebugLog.event("script-loader", "network error url=${DebugLog.safeUrl(request.url.toString())} code=${error.errorCode} ${error.description}")
                if (request.isForMainFrame) finish(Result.failure(IllegalStateException(error.description.toString())))
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                finish(Result.failure(IllegalStateException("脚本准备进程已退出，请重试")))
                return true
            }
        }
        WebViewCompat.addWebMessageListener(loader, "LightboatLoader", setOf(origin)) { _, message, source, mainFrame, _ ->
            if (!mainFrame || source.scheme != parsed.scheme.lowercase() ||
                source.host != parsed.host.lowercase() || view !== loader) return@addWebMessageListener
            val result = runCatching {
                val payload = JSONObject(message.data ?: error("脚本数据为空"))
                if (payload.has("error")) error(payload.getString("error"))
                payload
            }
            finish(result)
        }
        timeout = Runnable {
            DebugLog.event("script-loader", "timeout mode=$mode url=${DebugLog.safeUrl(url)}")
            finish(Result.failure(IllegalStateException("脚本请求超时，请检查网络后重试")))
        }
            .also { handler.postDelayed(it, 30_000) }
        val quotedUrl = JSONObject.quote(url).replace("<", "\\u003c")
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>" +
            "<script>const scriptUrl=$quotedUrl;const assetMode=${JSONObject.quote(mode)};\n$fetchCode</script></body></html>"
        // HTTP(S) base URL gives this private helper the script origin for same-origin fetch.
        loader.loadDataWithBaseURL("$origin/", html, "text/html", "UTF-8", null)
    }

    fun cancel() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        val hadActiveRequest = activeRequest != 0L
        activeRequest = 0L
        if (hadActiveRequest) view?.let { loader ->
            runCatching { WebViewCompat.removeWebMessageListener(loader, "LightboatLoader") }
            runCatching { loader.stopLoading() }
        }
    }

    fun close() {
        cancel()
        view?.let { loader ->
            view = null
            systemUserAgentMetadata = null
            runCatching { WebViewCompat.removeWebMessageListener(loader, "LightboatLoader") }
            runCatching { container.removeView(loader) }
            runCatching { loader.stopLoading() }
            // Keep the renderer-exit handler until destroy completes. A default
            // WebViewClient would treat a late renderer exit as unhandled.
            runCatching { loader.destroy() }.onFailure {
                DebugLog.event("script-loader", "destroy failed ${it.message.orEmpty().take(200)}")
            }
        }
    }

    private fun applyUserAgentMetadata(loader: WebView, userAgent: String, platform: BrowserPlatform) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val metadata = MobileUserAgent.metadata(platform, userAgent, systemUserAgentMetadata)
        if (metadata != null) WebSettingsCompat.setUserAgentMetadata(loader.settings, metadata)
    }
}

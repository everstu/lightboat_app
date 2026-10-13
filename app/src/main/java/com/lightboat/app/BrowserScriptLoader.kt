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
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.net.URI

/** Fetches text through Chromium's HTTP cache, without executing downloaded code. */
class BrowserScriptLoader(
    private val context: Context,
    private val container: ViewGroup
) {
    private var view: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun load(url: String, callback: (Result<String>) -> Unit) {
        close()
        val parsed = URI(url)
        val origin = URI(parsed.scheme.lowercase(), null, parsed.host.lowercase(), parsed.port, null, null, null).toString()
        val loader = WebView(context)
        view = loader
        loader.alpha = 0f
        loader.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        container.addView(loader, ViewGroup.LayoutParams(1, 1))
        loader.settings.apply {
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkImage = true
        }
        fun finish(result: Result<String>) {
            if (view !== loader) return
            close()
            callback(result)
        }
        loader.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
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
                payload.getString("script")
            }
            finish(result)
        }
        timeout = Runnable { finish(Result.failure(IllegalStateException("脚本请求超时，请检查网络后重试"))) }
            .also { handler.postDelayed(it, 30_000) }
        val fetchCode = context.assets.open("fetch-script.js").bufferedReader().use { it.readText() }
        val quotedUrl = JSONObject.quote(url).replace("<", "\\u003c")
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>" +
            "<script>const scriptUrl=$quotedUrl;\n$fetchCode</script></body></html>"
        // HTTP(S) base URL gives this private helper the script origin for same-origin fetch.
        loader.loadDataWithBaseURL("$origin/", html, "text/html", "UTF-8", null)
    }

    fun close() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        view?.let { loader ->
            view = null
            container.removeView(loader)
            loader.stopLoading()
            loader.webViewClient = WebViewClient()
            loader.destroy()
        }
    }
}

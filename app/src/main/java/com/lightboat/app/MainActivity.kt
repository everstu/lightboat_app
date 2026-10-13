package com.lightboat.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import com.lightboat.app.databinding.ActivityMainBinding
import com.lightboat.app.databinding.ItemProfileBinding
import com.lightboat.app.databinding.DialogBrowserMenuBinding
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ConfigStore
    private lateinit var scriptLoader: BrowserScriptLoader
    private var config = AppConfig.EMPTY
    private var webView: AppWebView? = null
    private val scriptHandlers = mutableListOf<ScriptHandler>()
    private val worker = Executors.newFixedThreadPool(2)
    private var generation = 0
    private var currentId: String? = null
    private var editingId: String? = null
    private var pageFailed = false
    private var pageReady = false
    private var screen = Screen.PROFILES
    private var exportSnapshot: String? = null
    private var scanTarget: ScanTarget? = null
    private var nativeDialog: Dialog? = null
    private lateinit var defaultUserAgent: String
    private lateinit var fullscreenPlayer: FullscreenPlayer

    private enum class Screen { PROFILES, EDITOR, LOADING, BROWSER, ERROR }
    private enum class ScanTarget { WEBSITE, SCRIPT, COPY }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val target = scanTarget
        scanTarget = null
        updateChrome()
        if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
            showCameraPermissionHelp()
        } else if (target != null && result.contents != null) {
            applyScanResult(target, result.contents)
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importConfig(uri)
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportSnapshot
        exportSnapshot = null
        if (uri != null && text != null) worker.execute {
            try {
                (contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文件"))
                    .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                runOnUiThread { if (!isDestroyed) toast(getString(R.string.export_done)) }
            } catch (error: Exception) { reportOperationError(error) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fullscreenPlayer = FullscreenPlayer(this, binding.root) { updateChrome() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (screen == Screen.BROWSER) {
                // The webpage fills the physical screen, including the cutout area.
                view.setPadding(0, 0, 0, keyboard.bottom)
            } else {
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            }
            // Only interactive native controls avoid the notch; do not inset the WebView.
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val menuBounds = binding.menuLayer.layoutParams as FrameLayout.LayoutParams
            if (menuBounds.leftMargin != cutout.left || menuBounds.topMargin != cutout.top ||
                menuBounds.rightMargin != cutout.right || menuBounds.bottomMargin != cutout.bottom) {
                menuBounds.setMargins(cutout.left, cutout.top, cutout.right, cutout.bottom)
                binding.menuLayer.layoutParams = menuBounds
            }
            insets
        }
        store = ConfigStore(this)
        defaultUserAgent = MobileUserAgent.default(this)
        scriptLoader = BrowserScriptLoader(this, binding.webContainer)
        binding.edgeMenuButton.setOnClickListener { showBrowserMenu() }
        binding.scanWebsiteButton.setOnClickListener { startScan(ScanTarget.WEBSITE) }
        binding.scanScriptButton.setOnClickListener { startScan(ScanTarget.SCRIPT) }
        binding.resetUserAgentButton.setOnClickListener {
            binding.userAgentInput.setText(defaultUserAgent)
            binding.userAgentInput.error = null
        }
        binding.settingsButton.setOnClickListener { showProfiles() }
        binding.editButton.setOnClickListener { showProfiles() }
        binding.refreshButton.setOnClickListener { currentProfile()?.let { openProfile(it) } }
        binding.retryButton.setOnClickListener { currentProfile()?.let { openProfile(it) } ?: launchLast() }
        binding.addButton.setOnClickListener { showEditor(null) }
        binding.saveButton.setOnClickListener { saveEditor() }
        binding.cancelButton.setOnClickListener { showProfiles() }
        binding.returnButton.setOnClickListener { returnToPage() }
        binding.importButton.setOnClickListener { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        binding.exportButton.setOnClickListener {
            exportSnapshot = ConfigCodec.encode(config)
            exportFile.launch("lightboat-config.json")
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenPlayer.isActive) { fullscreenPlayer.hide(); return }
                when (screen) {
                    Screen.EDITOR -> showProfiles()
                    Screen.PROFILES -> if (pageReady && webView != null) returnToPage() else confirmExit()
                    Screen.LOADING, Screen.ERROR -> showProfiles()
                    Screen.BROWSER -> if (webView?.canGoBack() == true) webView?.goBack() else confirmExit()
                }
            }
        })
        config = try { store.load() } catch (error: Exception) {
            toast(getString(R.string.config_read_error))
            AppConfig.EMPTY
        }
        exportSnapshot = savedInstanceState?.getString("exportSnapshot")
        scanTarget = savedInstanceState?.getString("scanTarget")?.let { value ->
            ScanTarget.entries.firstOrNull { it.name == value }
        }
        binding.edgeMenuButton.restoreAnchor(savedInstanceState?.getBoolean("menuLeft") ?: false,
            savedInstanceState?.getFloat("menuHeight", 0.68f) ?: 0.68f)
        if (savedInstanceState?.getBoolean("editor") == true) {
            showEditor(config.profiles.firstOrNull { it.id == savedInstanceState.getString("editingId") })
            binding.nameInput.setText(savedInstanceState.getString("draftName"))
            binding.websiteInput.setText(savedInstanceState.getString("draftUrl"))
            binding.scriptInput.setText(savedInstanceState.getString("draftScript"))
            binding.userAgentInput.setText(savedInstanceState.getString("draftUserAgent", defaultUserAgent))
        } else if (savedInstanceState?.getBoolean("manager") == true) showProfiles() else launchLast()
    }

    private fun launchLast() {
        config.lastProfile?.let { openProfile(it) } ?: showProfiles()
    }

    private fun currentProfile() = config.profiles.firstOrNull { it.id == currentId }

    private fun showScreen(next: Screen) {
        screen = next
        binding.configPanel.visibility = if (next == Screen.EDITOR) View.VISIBLE else View.GONE
        binding.profilesPanel.visibility = if (next == Screen.PROFILES) View.VISIBLE else View.GONE
        binding.browserPanel.visibility = if (next == Screen.BROWSER || next == Screen.LOADING) View.VISIBLE else View.GONE
        binding.statusPanel.visibility = if (next == Screen.LOADING || next == Screen.ERROR) View.VISIBLE else View.GONE
        binding.settingsButton.visibility = if (next in listOf(Screen.BROWSER, Screen.LOADING, Screen.ERROR)) View.VISIBLE else View.GONE
        binding.refreshButton.visibility = if (next == Screen.BROWSER) View.VISIBLE else View.GONE
        binding.titleBar.visibility = if (next == Screen.BROWSER) View.GONE else View.VISIBLE
        binding.menuLayer.visibility = if (next == Screen.BROWSER) View.VISIBLE else View.GONE
        if (next == Screen.BROWSER) binding.edgeMenuButton.wake()
        updateChrome()
    }

    private fun updateChrome() {
        if (!::binding.isInitialized) return
        val immersive = fullscreenPlayer.isActive ||
            (screen == Screen.BROWSER && scanTarget == null && nativeDialog == null)
        binding.menuLayer.visibility = if (screen == Screen.BROWSER && !fullscreenPlayer.isActive) View.VISIBLE else View.GONE
        WindowCompat.getInsetsController(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = when {
                    screen != Screen.BROWSER -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun showNativeDialog(dialog: Dialog) {
        nativeDialog?.dismiss()
        nativeDialog = dialog
        dialog.setOnDismissListener {
            if (nativeDialog === dialog) {
                nativeDialog = null
                updateChrome()
            }
        }
        updateChrome()
        dialog.show()
        dialog.window?.let { dialogWindow ->
            WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showBrowserMenu() {
        if (screen != Screen.BROWSER) return
        binding.edgeMenuButton.wake()
        val menu = DialogBrowserMenuBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(menu.root)
        menu.menuProfileName.text = currentProfile()?.name ?: getString(R.string.app_name)
        menu.menuSwitch.setOnClickListener { dialog.dismiss(); showProfilePicker() }
        menu.menuScan.setOnClickListener { dialog.dismiss(); startScan(ScanTarget.COPY) }
        menu.menuRefresh.setOnClickListener { dialog.dismiss(); currentProfile()?.let { openProfile(it) } }
        menu.menuSettings.setOnClickListener { dialog.dismiss(); showProfiles() }
        menu.menuClose.setOnClickListener { dialog.dismiss() }
        dialog.setCanceledOnTouchOutside(true)
        showNativeDialog(dialog)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.25f)
        }
    }

    private fun showProfilePicker() {
        val profiles = config.profiles
        val selected = profiles.indexOfFirst { it.id == currentId }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.switch_configuration)
            .setSingleChoiceItems(profiles.map { it.name }.toTypedArray(), selected) { picker, index ->
                picker.dismiss()
                val profile = profiles[index]
                if (profile.id == currentId && webView != null && pageReady) returnToPage() else openProfile(profile)
            }.setNegativeButton(R.string.cancel, null).create()
        showNativeDialog(dialog)
    }

    private fun startScan(target: ScanTarget) {
        if (scanTarget != null) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast(getString(R.string.camera_unavailable))
            return
        }
        hideKeyboard()
        scanTarget = target
        updateChrome()
        val options = ScanOptions().setCaptureActivity(QrScannerActivity::class.java)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt))
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)
        try { scanner.launch(options) } catch (error: Exception) {
            scanTarget = null
            updateChrome()
            toast(getString(R.string.operation_error, error.message.orEmpty()))
        }
    }

    private fun applyScanResult(target: ScanTarget, content: String) {
        if (target == ScanTarget.COPY) {
            val dialog = AlertDialog.Builder(this).setTitle(R.string.scan_result).setMessage(content)
                .setPositiveButton(R.string.copy) { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(getString(R.string.scan_result), content))
                    if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied))
                }
                .setNeutralButton(R.string.scan_again) { _, _ -> startScan(ScanTarget.COPY) }
                .setNegativeButton(R.string.close, null).create()
            showNativeDialog(dialog)
            return
        }
        val address = try { AppConfig.normalizeUrl(content) } catch (_: IllegalArgumentException) {
            toast(getString(R.string.scan_invalid))
            return
        }
        // Return to the same editor; scanner never saves or opens a URL automatically.
        val field = if (target == ScanTarget.WEBSITE) binding.websiteInput else binding.scriptInput
        field.setText(address)
        field.error = null
    }

    private fun showCameraPermissionHelp() {
        val dialog = AlertDialog.Builder(this).setTitle(R.string.camera_permission_title)
            .setMessage(R.string.camera_permission_message)
            .setPositiveButton(R.string.open_system_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.setNegativeButton(R.string.close, null).create()
        showNativeDialog(dialog)
    }

    private fun cancelDownload() {
        generation++
        scriptLoader.close()
    }

    private fun showProfiles() {
        cancelDownload()
        hideKeyboard()
        showScreen(Screen.PROFILES)
        binding.emptyProfiles.visibility = if (config.profiles.isEmpty()) View.VISIBLE else View.GONE
        binding.returnButton.visibility = if (pageReady && webView != null) View.VISIBLE else View.GONE
        binding.profilesList.removeAllViews()
        binding.addButton.isEnabled = config.profiles.size < AppConfig.MAX_PROFILES
        config.profiles.forEach { profile ->
            val row = ItemProfileBinding.inflate(layoutInflater, binding.profilesList, false)
            row.profileName.text = profile.name
            row.profileUrl.text = profile.url
            row.profileBadge.text = listOfNotNull(
                getString(R.string.last_profile).takeIf { profile.id == config.lastProfileId },
                getString(R.string.current_profile).takeIf { profile.id == currentId && webView != null }
            ).joinToString(" · ")
            row.profileBadge.visibility = if (row.profileBadge.text.isEmpty()) View.GONE else View.VISIBLE
            row.openProfile.setOnClickListener { openProfile(profile) }
            row.editProfile.setOnClickListener { showEditor(profile) }
            row.deleteProfile.setOnClickListener {
                AlertDialog.Builder(this).setTitle(R.string.delete_title).setMessage(R.string.delete_message)
                    .setNegativeButton(R.string.stay, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        if (persist(config.remove(profile.id))) {
                            if (currentId == profile.id) { destroyWebView(); currentId = null }
                            showProfiles()
                        }
                    }.show()
            }
            binding.profilesList.addView(row.root)
        }
    }

    private fun showEditor(profile: Profile?) {
        editingId = profile?.id
        showScreen(Screen.EDITOR)
        binding.configTitle.setText(if (profile == null) R.string.setup_title else R.string.edit_profile)
        binding.configDescription.setText(if (profile == null) R.string.setup_description else R.string.edit_profile_description)
        binding.nameInput.setText(profile?.name.orEmpty())
        binding.websiteInput.setText(profile?.url.orEmpty())
        binding.scriptInput.setText(profile?.scriptUrl.orEmpty())
        binding.userAgentInput.setText(profile?.userAgent ?: defaultUserAgent)
        binding.nameInput.error = null
        binding.websiteInput.error = null
        binding.scriptInput.error = null
        binding.userAgentInput.error = null
        binding.saveButton.setText(R.string.save_profile)
        binding.cancelButton.setText(R.string.back_profiles)
        binding.cancelButton.visibility = View.VISIBLE
    }

    private fun saveEditor() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isBlank()) { binding.nameInput.error = "请填写配置名称"; return }
        val url = try { AppConfig.normalizeUrl(binding.websiteInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.websiteInput.error = error.message; return
        }
        val scriptUrl = try { AppConfig.normalizeUrl(binding.scriptInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.scriptInput.error = error.message; return
        }
        val userAgent = try { AppConfig.normalizeUserAgent(binding.userAgentInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.userAgentInput.error = error.message; return
        }
        val profile = Profile(editingId ?: UUID.randomUUID().toString(), name, url, scriptUrl, userAgent)
        val next = try { config.upsert(profile).select(profile.id) } catch (error: Exception) {
            toast(error.message.orEmpty()); return
        }
        if (persist(next)) { hideKeyboard(); openProfile(profile) }
    }

    private fun persist(next: AppConfig): Boolean = try {
        store.save(next)
        config = next
        true
    } catch (error: Exception) {
        toast(getString(R.string.operation_error, error.message.orEmpty()))
        false
    }

    private fun openProfile(profile: Profile) {
        if (config.lastProfileId != profile.id && !persist(config.select(profile.id))) return
        hideKeyboard()
        cancelDownload()
        destroyWebView()
        currentId = profile.id
        showLoading(R.string.preparing)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showError(R.string.unsupported_title, getString(R.string.unsupported_message))
            return
        }
        val request = generation
        try {
            scriptLoader.load(profile.scriptUrl, profile.userAgent) { result ->
                if (!isDestroyed && request == generation) {
                    result.fold(onSuccess = { script ->
                        try {
                            val guard = assets.open("native-guard.js").bufferedReader().use { it.readText() }
                            createWebView(profile, guard, script)
                        } catch (error: Exception) {
                            destroyWebView()
                            showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
                        }
                    }, onFailure = { error ->
                        showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
                    })
                }
            }
        } catch (error: Exception) {
            scriptLoader.close()
            showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(profile: Profile, guard: String, script: String) {
        val view = AppWebView(this)
        webView = view
        binding.webContainer.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        view.settings.apply {
            userAgentString = profile.userAgent
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(customView: View, callback: WebChromeClient.CustomViewCallback) {
                if (view !== webView || screen != Screen.BROWSER) { callback.onCustomViewHidden(); return }
                hideKeyboard()
                fullscreenPlayer.show(customView, callback)
            }

            override fun onHideCustomView() { fullscreenPlayer.hide() }

            override fun onProgressChanged(view: WebView, progress: Int) {
                if (view !== webView || pageFailed) return
                binding.pageProgress.progress = progress
                binding.pageProgress.visibility = if (progress < 100) View.VISIBLE else View.GONE
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme?.lowercase() in setOf("http", "https")) return false
                if (request.isForMainFrame) toast(getString(R.string.unsafe_link))
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (view !== webView) return
                fullscreenPlayer.hide()
                (view as AppWebView).clearClipboardGesture()
                pageFailed = false
                binding.pageProgress.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                revealPage(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== webView || pageFailed) return
                revealPage(view)
                binding.pageProgress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && view === webView) failPage(error.description.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && view === webView) {
                    failPage(getString(R.string.page_http_error, response.statusCode))
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (view === webView) failPage(getString(R.string.bad_certificate))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    destroyWebView()
                    showError(R.string.page_error_title, getString(R.string.renderer_error))
                }
                return true
            }
        }
        // Register separate scripts so a syntax error in supplied JS cannot disable the guard.
        // Wildcard deliberately covers redirects, subsequent navigation and child frames.
        WebViewCompat.addWebMessageListener(view, "LightboatClipboard", setOf("*")) { sender, message, origin, _, reply ->
            if (sender !== webView || message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val payload = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@addWebMessageListener
            val id = payload.optString("id")
            if (id.isEmpty() || id.length > 64) return@addWebMessageListener
            val response = JSONObject().put("id", id)
            try {
                val secure = origin.scheme == "https" || (origin.scheme == "http" &&
                    origin.host in setOf("localhost", "127.0.0.1", "[::1]", "::1"))
                check(secure && screen == Screen.BROWSER && view.isShown && hasWindowFocus() && view.consumeClipboardGesture()) {
                    "复制需要在当前网页中点击操作"
                }
                val text = payload.getString("text")
                require(text.length <= 65536) { "复制文本过长" }
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
            } catch (error: Exception) { response.put("error", error.message ?: "无法复制") }
            reply.postMessage(response.toString())
        }
        val clipboardSupport = assets.open("clipboard-support.js").bufferedReader().use { it.readText() }
        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view, guard, setOf("*"))
        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view, clipboardSupport, setOf("*"))
        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
        showLoading(R.string.loading_page)
        view.loadUrl(profile.url)
    }

    private fun revealPage(view: WebView) {
        if (view !== webView || pageFailed) return
        pageReady = true
        if (screen == Screen.LOADING) showScreen(Screen.BROWSER)
        if (screen == Screen.PROFILES) binding.returnButton.visibility = View.VISIBLE
    }

    private fun failPage(message: String) {
        fullscreenPlayer.hide()
        pageFailed = true
        pageReady = false
        webView?.stopLoading()
        if (screen in listOf(Screen.BROWSER, Screen.LOADING, Screen.ERROR)) {
            showError(R.string.page_error_title, message)
        }
    }

    private fun returnToPage() {
        if (webView != null && pageReady) showScreen(Screen.BROWSER) else launchLast()
    }

    private fun showLoading(title: Int) {
        showScreen(Screen.LOADING)
        binding.statusTitle.setText(title)
        binding.statusDetail.setText(R.string.preparing_note)
        binding.statusSpinner.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.editButton.visibility = View.GONE
    }

    private fun showError(title: Int, message: String) {
        showScreen(Screen.ERROR)
        binding.statusTitle.setText(title)
        binding.statusDetail.text = message
        binding.statusSpinner.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.editButton.visibility = View.VISIBLE
        binding.pageProgress.visibility = View.GONE
    }

    private fun importConfig(uri: Uri) {
        worker.execute {
            try {
                val text = (contentResolver.openInputStream(uri) ?: error("无法读取所选文件"))
                    .bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(4096)
                        val output = StringBuilder()
                        while (true) {
                            val size = reader.read(buffer)
                            if (size == -1) break
                            require(output.length + size <= 1_048_576) { "配置文件不能超过 1 MiB 字符" }
                            output.append(buffer, 0, size)
                        }
                        output.toString()
                    }
                val imported = ConfigCodec.decode(text, defaultUserAgent)
                runOnUiThread {
                    if (!isDestroyed) AlertDialog.Builder(this).setTitle(R.string.import_title)
                        .setMessage(getString(R.string.import_message, imported.profiles.size))
                        .setNegativeButton(R.string.stay, null)
                        .setPositiveButton(R.string.import_confirm) { _, _ ->
                            if (persist(imported)) {
                                cancelDownload(); destroyWebView(); currentId = null
                                launchLast()
                            }
                        }.show()
                }
            } catch (error: Exception) { reportOperationError(error) }
        }
    }

    private fun reportOperationError(error: Exception) {
        runOnUiThread { if (!isDestroyed) toast(getString(R.string.operation_error, error.message.orEmpty())) }
    }

    private fun confirmExit() {
        showNativeDialog(AlertDialog.Builder(this).setTitle(R.string.exit_title).setMessage(R.string.exit_message)
            .setNegativeButton(R.string.stay, null).setPositiveButton(R.string.exit) { _, _ -> finish() }.create())
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.root.clearFocus()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun destroyWebView() {
        fullscreenPlayer.hide()
        scriptHandlers.forEach { runCatching { it.remove() } }
        scriptHandlers.clear()
        webView?.let { view ->
            binding.webContainer.removeView(view)
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        webView = null
        pageReady = false
        pageFailed = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("exportSnapshot", exportSnapshot)
        outState.putBoolean("manager", screen == Screen.PROFILES)
        outState.putBoolean("editor", screen == Screen.EDITOR)
        outState.putString("editingId", editingId)
        outState.putString("draftName", binding.nameInput.text.toString())
        outState.putString("draftUrl", binding.websiteInput.text.toString())
        outState.putString("draftScript", binding.scriptInput.text.toString())
        outState.putString("draftUserAgent", binding.userAgentInput.text.toString())
        outState.putString("scanTarget", scanTarget?.name)
        outState.putBoolean("menuLeft", binding.edgeMenuButton.leftEdge)
        outState.putFloat("menuHeight", binding.edgeMenuButton.heightFraction)
    }

    override fun onPause() { webView?.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onResume() { super.onResume(); webView?.onResume(); updateChrome() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateChrome()
    }
    override fun onDestroy() {
        nativeDialog?.setOnDismissListener(null)
        nativeDialog?.dismiss()
        nativeDialog = null
        cancelDownload()
        worker.shutdownNow()
        destroyWebView()
        super.onDestroy()
    }
}

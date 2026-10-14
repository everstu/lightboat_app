package com.lightboat.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
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
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
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
import androidx.webkit.UserAgentMetadata
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import com.lightboat.app.databinding.ActivityMainBinding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightboat.app.databinding.DialogBrowserMenuBinding
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.lightboat.app.userscript.ScriptBundle
import com.lightboat.app.userscript.ScriptPreparer
import com.lightboat.app.userscript.UserScriptHost

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ConfigStore
    private lateinit var scriptLoader: BrowserScriptLoader
    private var config = AppConfig.EMPTY
    private var webView: AppWebView? = null
    private var configuredWebView: AppWebView? = null
    private var systemUserAgentMetadata: UserAgentMetadata? = null
    private var mediaProxy: MediaRequestProxy? = null
    private var profileNetwork: ProfileRequestClient? = null
    @Volatile private var requestInterceptor: ProfileRequestInterceptor? = null
    /** One transport per profile session: reuse connections during playback, reset on refresh/switch. */
    private fun createProfileTransport(): okhttp3.OkHttpClient {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        }
        return okhttp3.OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }
    private val scriptHandlers = mutableListOf<ScriptHandler>()
    private val worker = Executors.newFixedThreadPool(2)
    private var generation = 0
    private var navigationGeneration = 0L
    private var currentId: String? = null
    private var editingId: String? = null
    private var pageFailed = false
    private var pageReady = false
    private var screen = Screen.PROFILES
    private var exportSnapshot: String? = null
    private var scanTarget: ScanTarget? = null
    private var nativeDialog: Dialog? = null
    private lateinit var defaultUserAgent: String
    private lateinit var appleUserAgent: String
    private lateinit var windowsUserAgent: String
    private var editingPlatform = BrowserPlatform.ANDROID
    private lateinit var fullscreenPlayer: FullscreenPlayer
    private var userScriptHost: UserScriptHost? = null
    private lateinit var profileAdapter: ProfileAdapter
    private lateinit var profileDrag: ItemTouchHelper
    private var profileOrderDirty = false
    private var settingsInfoInitialized = false
    private val nativeGuardScript by lazy(LazyThreadSafetyMode.NONE) { readAssetText("native-guard.js") }
    private val clipboardSupportScript by lazy(LazyThreadSafetyMode.NONE) { readAssetText("clipboard-support.js") }
    private val mediaDiagnosticsScript by lazy(LazyThreadSafetyMode.NONE) { readAssetText("media-diagnostics.js") }
    private val mediaRoutingScript by lazy(LazyThreadSafetyMode.NONE) { readAssetText("media-request-routing.js") }

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
        DebugLog.initialize(this)
        DebugLog.event("activity", "onCreate restored=${savedInstanceState != null}")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fullscreenPlayer = FullscreenPlayer(this, binding.root) { updateChrome() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (screen == Screen.BROWSER) {
                // The webpage fills the physical screen, including the cutout area.
                left = 0; top = 0; right = 0; bottom = keyboard.bottom
            } else {
                left = bars.left; top = bars.top; right = bars.right
                bottom = maxOf(bars.bottom, keyboard.bottom)
            }
            if (view.paddingLeft != left || view.paddingTop != top ||
                view.paddingRight != right || view.paddingBottom != bottom) {
                view.setPadding(left, top, right, bottom)
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
        defaultUserAgent = MobileUserAgent.default(this)
        appleUserAgent = MobileUserAgent.appleSafari()
        windowsUserAgent = MobileUserAgent.windowsChrome(defaultUserAgent)
        store = ConfigStore(this, defaultUserAgent)
        scriptLoader = BrowserScriptLoader(this, binding.webContainer)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    requestInterceptor?.intercept(request)
            })
        }
        binding.edgeMenuButton.setOnClickListener { showBrowserMenu() }
        binding.scanWebsiteButton.setOnClickListener { startScan(ScanTarget.WEBSITE) }
        binding.scanScriptButton.setOnClickListener { startScan(ScanTarget.SCRIPT) }
        binding.applePlatformButton.setOnClickListener { selectPlatform(BrowserPlatform.APPLE, true) }
        binding.androidPlatformButton.setOnClickListener { selectPlatform(BrowserPlatform.ANDROID, true) }
        binding.windowsPlatformButton.setOnClickListener { selectPlatform(BrowserPlatform.WINDOWS, true) }
        binding.settingsButton.setOnClickListener { showProfiles() }
        binding.editButton.setOnClickListener { showProfiles() }
        binding.refreshButton.setOnClickListener { currentProfile()?.let { openProfile(it) } }
        binding.retryButton.setOnClickListener { currentProfile()?.let { openProfile(it) } ?: launchLast() }
        binding.debugLogButton.setOnClickListener { showScriptLogs() }
        binding.addButton.setOnClickListener { showEditor(null) }
        binding.saveButton.setOnClickListener { saveEditor(false) }
        binding.saveOpenButton.setOnClickListener { saveEditor(true) }
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
            savedInstanceState.getString("draftPlatform")?.let {
                selectPlatform(BrowserPlatform.parse(it), false)
            }
        } else if (savedInstanceState?.getBoolean("manager") == true) showProfiles() else launchLast()
    }

    private fun launchLast() {
        config.lastProfile?.let { openProfile(it) } ?: showProfiles()
    }

    private fun showAppVersion() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        binding.versionLabel.text = getString(
            R.string.app_version,
            packageInfo.versionName.orEmpty(),
            versionCode,
        )
    }

    private fun currentProfile() = config.profiles.firstOrNull { it.id == currentId }

    private fun showScreen(next: Screen) {
        if (screen == Screen.PROFILES && next != Screen.PROFILES) commitProfileOrder()
        screen = next
        binding.configPanel.visibility = if (next == Screen.EDITOR) View.VISIBLE else View.GONE
        binding.profilesPanel.visibility = if (next == Screen.PROFILES) View.VISIBLE else View.GONE
        binding.browserPanel.visibility = when {
            next == Screen.BROWSER || next == Screen.LOADING -> View.VISIBLE
            webView != null && pageReady -> View.INVISIBLE
            else -> View.GONE
        }
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
            val desiredCutoutMode = when {
                screen != Screen.BROWSER -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val attributes = window.attributes
            if (attributes.layoutInDisplayCutoutMode != desiredCutoutMode) {
                attributes.layoutInDisplayCutoutMode = desiredCutoutMode
                window.attributes = attributes
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
        menu.menuScriptLogs.setOnClickListener { dialog.dismiss(); showScriptLogs() }
        menu.menuScriptCommands.isEnabled = userScriptHost?.menus?.isNotEmpty() == true
        menu.menuScriptCommands.setOnClickListener { dialog.dismiss(); showScriptCommands() }
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

    private fun showScriptLogs() {
        val logView = android.widget.TextView(this).apply {
            text = DebugLog.read().ifBlank { "暂无应用调试日志" }
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            textSize = 12f
        }
        val scroll = android.widget.ScrollView(this).apply { addView(logView) }
        val text = logView.text.toString()
        showNativeDialog(AlertDialog.Builder(this).setTitle(R.string.debug_logs).setView(scroll)
            .setPositiveButton(R.string.copy) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.debug_logs), text))
                if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied))
            }.setNegativeButton(R.string.close, null).create())
    }

    private fun showScriptCommands() {
        val host = userScriptHost ?: return
        val commands = host.menus
        showNativeDialog(AlertDialog.Builder(this).setTitle(R.string.script_commands)
            .setItems(commands.map { it.title }.toTypedArray()) { dialog, index ->
                dialog.dismiss()
                binding.root.post { if (userScriptHost === host) host.invoke(commands[index]) }
            }.setNegativeButton(R.string.close, null).create())
    }

    private fun showProfilePicker() {
        val profiles = config.profiles
        val selected = profiles.indexOfFirst { it.id == currentId }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.switch_configuration)
            .setSingleChoiceItems(profiles.map { it.name }.toTypedArray(), selected) { picker, index ->
                picker.dismiss()
                val profile = profiles[index]
                openProfile(profile)
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
        DebugLog.event("script", "cancel generation=$generation")
        scriptLoader.cancel()
    }

    private fun showProfiles() {
        cancelDownload()
        scriptLoader.close()
        ensureSettingsUi()
        hideKeyboard()
        showScreen(Screen.PROFILES)
        binding.emptyProfiles.visibility = if (config.profiles.isEmpty()) View.VISIBLE else View.GONE
        binding.returnButton.visibility = if (pageReady && webView != null) View.VISIBLE else View.GONE
        binding.addButton.isEnabled = config.profiles.size < AppConfig.MAX_PROFILES
        binding.addButton.alpha = if (binding.addButton.isEnabled) 1f else 0.4f
        profileOrderDirty = false
        profileAdapter.submit(config, currentId.takeIf { webView != null })
    }

    private fun setupProfileList() {
        profileAdapter = ProfileAdapter(::openProfile, ::showEditor, ::confirmDeleteProfile) { holder ->
            profileDrag.startDrag(holder)
        }
        binding.profilesList.layoutManager = LinearLayoutManager(this)
        binding.profilesList.setHasFixedSize(true)
        binding.profilesList.adapter = profileAdapter
        profileDrag = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(list: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val moved = profileAdapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                if (moved) profileOrderDirty = true
                return moved
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) commitProfileOrder()
            }

            override fun clearView(list: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(list, holder)
                commitProfileOrder()
            }
        })
        profileDrag.attachToRecyclerView(binding.profilesList)
    }

    private fun ensureSettingsUi() {
        if (!::profileAdapter.isInitialized) setupProfileList()
        if (!settingsInfoInitialized) {
            settingsInfoInitialized = true
            showAppVersion()
        }
    }

    private fun commitProfileOrder() {
        if (!::profileAdapter.isInitialized || !profileOrderDirty) return
        profileOrderDirty = false
        val ids = profileAdapter.orderedIds
        if (ids == config.profiles.map { it.id }) return
        // A configuration import/delete can invalidate an old drag animation's callback.
        if (ids.toSet() != config.profiles.map { it.id }.toSet()) return
        if (!persist(config.reorder(ids))) profileAdapter.submit(config, currentId.takeIf { webView != null })
    }

    private fun confirmDeleteProfile(profile: Profile) {
        commitProfileOrder()
        AlertDialog.Builder(this).setTitle(R.string.delete_title).setMessage(R.string.delete_message)
            .setNegativeButton(R.string.stay, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (persist(config.remove(profile.id))) {
                    if (currentId == profile.id) { destroyWebView(); currentId = null }
                    showProfiles()
                }
            }.show()
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
        selectPlatform(profile?.platform ?: BrowserPlatform.ANDROID, false)
        binding.nameInput.error = null
        binding.websiteInput.error = null
        binding.scriptInput.error = null
        binding.userAgentInput.error = null
        binding.saveButton.setText(R.string.save_profile)
        binding.saveOpenButton.setText(R.string.save_open)
        binding.cancelButton.setText(R.string.back_profiles)
        binding.cancelButton.visibility = View.VISIBLE
    }

    private fun selectPlatform(platform: BrowserPlatform, updateUserAgent: Boolean) {
        editingPlatform = platform
        if (updateUserAgent) {
            binding.userAgentInput.setText(when (platform) {
                BrowserPlatform.APPLE -> appleUserAgent
                BrowserPlatform.ANDROID -> defaultUserAgent
                BrowserPlatform.WINDOWS -> windowsUserAgent
            })
            binding.userAgentInput.error = null
        }
        listOf(
            BrowserPlatform.APPLE to binding.applePlatformButton,
            BrowserPlatform.ANDROID to binding.androidPlatformButton,
            BrowserPlatform.WINDOWS to binding.windowsPlatformButton,
        ).forEach { (item, button) ->
            button.isSelected = item == platform
            button.alpha = if (item == platform) 1f else 0.55f
        }
    }

    private fun saveEditor(openAfterSave: Boolean) {
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
        val profile = Profile(editingId ?: UUID.randomUUID().toString(), name, url, scriptUrl, userAgent, editingPlatform)
        val next = try {
            config.upsert(profile).let { if (openAfterSave) it.select(profile.id) else it }
        } catch (error: Exception) {
            toast(error.message.orEmpty()); return
        }
        if (persist(next)) {
            hideKeyboard()
            if (openAfterSave) openProfile(profile) else showProfiles()
        }
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
        DebugLog.event("profile", "open name=${profile.name.take(80)} url=${DebugLog.safeUrl(profile.url)} script=${DebugLog.safeUrl(profile.scriptUrl)} platform=${profile.platform}")
        if (config.lastProfileId != profile.id && !persist(config.select(profile.id))) return
        hideKeyboard()
        cancelDownload()
        // Every launch/switch gets a clean browser and script-loader renderer.
        // This prevents old document scripts, media sessions and renderer state
        // from leaking into the selected configuration.
        scriptLoader.close()
        destroyWebView()
        val navigationRequest = ++navigationGeneration
        currentId = profile.id
        showLoading(R.string.preparing)
        DebugLog.event("profile", "new session navigation=$navigationRequest generation=$generation")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showError(R.string.unsupported_title, getString(R.string.unsupported_message))
            return
        }
        val request = generation
        try {
            scriptLoader.load(profile.scriptUrl, profile.userAgent, profile.platform) { result ->
                DebugLog.event("script", "load result=${if (result.isSuccess) "success" else "failure:${result.exceptionOrNull()?.message.orEmpty().take(180)}"} url=${DebugLog.safeUrl(profile.scriptUrl)}")
                if (!isDestroyed && request == generation) {
                    result.fold(onSuccess = { script ->
                        try {
                            ScriptPreparer(scriptLoader).prepare(
                                script, profile.scriptUrl, profile.userAgent, profile.platform
                            ) { prepared ->
                                if (!isDestroyed && request == generation) {
                                    prepared.fold(onSuccess = { bundle ->
                                        DebugLog.event("script", "prepared name=${bundle.metadata.name.take(100)} runAt=${bundle.metadata.runAt} grants=${bundle.metadata.grants.size} sourceBytes=${bundle.source.length}")
                                        scriptLoader.close()
                                        // Let Chromium finish destroying the short-lived loader
                                        // before allocating the browsing WebView renderer.
                                        binding.root.post {
                                            if (!isDestroyed && request == generation && navigationRequest == navigationGeneration) {
                                                try {
                                                    prepareMainWebView(profile)
                                                    createWebView(profile, nativeGuardScript, bundle, navigationRequest)
                                                } catch (error: Exception) {
                                                    DebugLog.event("webview", "create failed ${error.message.orEmpty().take(240)}")
                                                    destroyWebView()
                                                    showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
                                                }
                                            }
                                        }
                                    }, onFailure = { error ->
                                        DebugLog.event("script", "prepare failed ${error.message.orEmpty().take(240)}")
                                        scriptLoader.close()
                                        showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
                                    })
                                }
                            }
                        } catch (error: Exception) {
                            DebugLog.event("script", "prepare exception ${error.message.orEmpty().take(240)}")
                            scriptLoader.close()
                            destroyWebView()
                            showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
                        }
                    }, onFailure = { error ->
                        DebugLog.event("script", "download failed ${error.message.orEmpty().take(240)}")
                        scriptLoader.close()
                        showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
                    })
                }
            }
        } catch (error: Exception) {
            DebugLog.event("script", "load exception ${error.message.orEmpty().take(240)}")
            scriptLoader.close()
            showError(R.string.script_error_title, error.message ?: getString(R.string.script_download_error))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(profile: Profile, guard: String, bundle: ScriptBundle, navigationRequest: Long) {
        DebugLog.event("webview", "create navigation=$navigationRequest url=${DebugLog.safeUrl(profile.url)}")
        val view = webView ?: AppWebView(this).also {
            webView = it
            binding.webContainer.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        fun isCurrentNavigation() = view === webView && navigationRequest == navigationGeneration
        val cookies = CookieManager.getInstance()
        val network = ProfileRequestClient(profile.userAgent, createProfileTransport(),
            cookies = { cookies.getCookie(it) }, saveCookie = { url, cookie -> cookies.setCookie(url, cookie) })
        profileNetwork = network
        val interceptor = ProfileRequestInterceptor(profile.userAgent, network)
        requestInterceptor = interceptor
        val proxy = MediaRequestProxy(network)
        mediaProxy = proxy
        proxy.start()
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (isCurrentNavigation()) userScriptHost?.console(message)
                return false
            }
            override fun onShowCustomView(customView: View, callback: WebChromeClient.CustomViewCallback) {
                if (!isCurrentNavigation() || screen != Screen.BROWSER) { callback.onCustomViewHidden(); return }
                hideKeyboard()
                fullscreenPlayer.show(customView, callback)
            }

            override fun onHideCustomView() { if (isCurrentNavigation()) fullscreenPlayer.hide() }

            override fun onPermissionRequest(request: PermissionRequest) {
                val protectedMedia = request.resources.filter {
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                }.toTypedArray()
                if (isCurrentNavigation() && request.origin.scheme == "https" && protectedMedia.isNotEmpty()) {
                    request.grant(protectedMedia)
                } else {
                    request.deny()
                }
            }

            override fun onProgressChanged(view: WebView, progress: Int) {
                if (!isCurrentNavigation() || pageFailed) return
                updatePageProgress(progress, progress < 100)
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor.intercept(request).also {
                    if (DebugLog.shouldTraceUrl(request.url.toString())) {
                        DebugLog.event("web-request", "${request.method} ${DebugLog.safeUrl(request.url.toString())} ua=${request.requestHeaders.keys.any { it.equals("User-Agent", true) }} intercepted=${it != null}")
                    }
                }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!isCurrentNavigation()) return true
                if (request.url.scheme?.lowercase() in setOf("http", "https")) return false
                if (request.isForMainFrame) toast(getString(R.string.unsafe_link))
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!isCurrentNavigation()) return
                DebugLog.event("page", "started ${DebugLog.safeUrl(url)}")
                userScriptHost?.resetDocuments()
                fullscreenPlayer.hide()
                (view as AppWebView).clearClipboardGesture()
                pageFailed = false
                updatePageProgress(0, true)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (!isCurrentNavigation()) return
                DebugLog.event("page", "committed ${DebugLog.safeUrl(url)}")
                userScriptHost?.pageCommitted(url)
                revealPage(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!isCurrentNavigation() || pageFailed) return
                DebugLog.event("page", "finished ${DebugLog.safeUrl(url)}")
                revealPage(view)
                updatePageProgress(100, false)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                DebugLog.event("page-error", "${request.method} ${DebugLog.safeUrl(request.url.toString())} code=${error.errorCode} ${error.description}")
                if (request.isForMainFrame && isCurrentNavigation()) failPage(error.description.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                DebugLog.event("http-error", "${request.method} ${DebugLog.safeUrl(request.url.toString())} status=${response.statusCode}")
                if (request.isForMainFrame && isCurrentNavigation()) {
                    failPage(getString(R.string.page_http_error, response.statusCode))
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (isCurrentNavigation()) failPage(getString(R.string.bad_certificate))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                DebugLog.event("webview", "renderer gone didCrash=${detail.didCrash()}")
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
            if (!isCurrentNavigation() || sender !== webView || message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
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
        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view, guard, setOf("*"))
        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view, clipboardSupportScript, setOf("*"))
        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view,
            mediaRoutingScript.replace("__LIGHTBOAT_MEDIA_PROXY__", JSONObject.quote(proxy.baseUrl)), setOf("*"))
        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view, mediaDiagnosticsScript, setOf("*"))
        MobileUserAgent.navigatorIdentityScript(profile.platform, profile.userAgent)?.let { identity ->
            scriptHandlers += WebViewCompat.addDocumentStartJavaScript(view, identity, setOf("*"))
        }
        userScriptHost = UserScriptHost(this, view, profile, bundle)
        scriptHandlers += userScriptHost!!.install()
        showLoading(R.string.loading_page)
        view.onResume()
        // Chromium requests use this identity; native media uses the session proxy.
        applyProfileUserAgent(view, profile)
        view.loadUrl(profile.url)
    }

    @SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
    private fun prepareMainWebView(profile: Profile) {
        val view = webView ?: AppWebView(this).also {
            webView = it
            binding.webContainer.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        if (configuredWebView !== view) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                systemUserAgentMetadata = WebSettingsCompat.getUserAgentMetadata(view.settings)
            }
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                // Legacy HLS manifests may still reference HTTP media segments from an HTTPS page.
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // The site controls its playlist; don't require another tap for every next item.
                mediaPlaybackRequiresUserGesture = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Keep the active renderer responsive while allowing the system
                // to reclaim it when the activity is no longer visible.
                view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // Embedded players commonly need their own session/progress cookie.
                setAcceptThirdPartyCookies(view, true)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
            }
            configuredWebView = view
        }
        applyProfileUserAgent(view, profile)
    }

    private fun applyProfileUserAgent(view: AppWebView, profile: Profile) {
        view.settings.userAgentString = profile.userAgent
        applyUserAgentMetadata(view, profile)
    }

    private fun applyUserAgentMetadata(view: AppWebView, profile: Profile) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val metadata = MobileUserAgent.metadata(profile.platform, profile.userAgent, systemUserAgentMetadata)
        if (metadata != null) WebSettingsCompat.setUserAgentMetadata(view.settings, metadata)
    }

    private fun updatePageProgress(progress: Int, visible: Boolean) {
        if (binding.pageProgress.progress != progress) binding.pageProgress.progress = progress
        val target = if (visible) View.VISIBLE else View.GONE
        if (binding.pageProgress.visibility != target) binding.pageProgress.visibility = target
    }

    private fun readAssetText(name: String) = assets.open(name).bufferedReader().use { it.readText() }

    private fun revealPage(view: WebView) {
        if (view !== webView || pageFailed) return
        pageReady = true
        if (screen == Screen.LOADING) showScreen(Screen.BROWSER)
        if (screen == Screen.PROFILES) binding.returnButton.visibility = View.VISIBLE
    }

    private fun failPage(message: String) {
        DebugLog.event("page-error", "main frame failed ${message.take(300)}")
        userScriptHost?.resetDocuments()
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
        DebugLog.event("ui-error", "${getString(title)} ${message.take(300)}")
        showScreen(Screen.ERROR)
        binding.statusTitle.setText(title)
        binding.statusDetail.text = message
        binding.statusSpinner.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.editButton.visibility = View.VISIBLE
        updatePageProgress(binding.pageProgress.progress, false)
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
                                profileOrderDirty = false
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
        DebugLog.event("webview", "destroy active=${webView != null} profile=${currentId.orEmpty()}")
        val retiringView = webView
        webView = null
        configuredWebView = null
        navigationGeneration++
        runCatching { userScriptHost?.close() }.onFailure {
            DebugLog.event("webview", "script host close failed ${it.message.orEmpty().take(200)}")
        }
        userScriptHost = null
        runCatching { fullscreenPlayer.hide() }.onFailure {
            DebugLog.event("webview", "fullscreen close failed ${it.message.orEmpty().take(200)}")
        }
        scriptHandlers.forEach { runCatching { it.remove() } }
        scriptHandlers.clear()
        retiringView?.let { view ->
            runCatching { WebViewCompat.removeWebMessageListener(view, "LightboatClipboard") }
            runCatching { WebViewCompat.removeWebMessageListener(view, "LightboatGM") }
            runCatching { binding.webContainer.removeView(view) }
            runCatching { view.stopLoading() }
            runCatching { view.onPause() }
            runCatching { view.webChromeClient = null }
            runCatching { view.webViewClient = WebViewClient() }
            binding.root.post {
                runCatching { view.destroy() }.onFailure {
                    DebugLog.event("webview", "destroy failed ${it.message.orEmpty().take(200)}")
                }
            }
        }
        closeProfileNetwork()
        pageReady = false
        pageFailed = false
    }

    private fun closeProfileNetwork() {
        DebugLog.event("network", "close mediaProxy=${mediaProxy != null} profileNetwork=${profileNetwork != null}")
        requestInterceptor = null
        val retiringProxy = mediaProxy
        val retiringNetwork = profileNetwork
        mediaProxy = null
        profileNetwork = null
        runCatching { retiringProxy?.close() }.onFailure {
            DebugLog.event("network", "media proxy close failed ${it.message.orEmpty().take(200)}")
        }
        runCatching { retiringNetwork?.close() }.onFailure {
            DebugLog.event("network", "profile network close failed ${it.message.orEmpty().take(200)}")
        }
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
        outState.putString("draftPlatform", editingPlatform.configValue)
        outState.putString("scanTarget", scanTarget?.name)
        outState.putBoolean("menuLeft", binding.edgeMenuButton.leftEdge)
        outState.putFloat("menuHeight", binding.edgeMenuButton.heightFraction)
    }

    override fun onPause() {
        if (screen == Screen.PROFILES) commitProfileOrder()
        super.onPause()
    }
    override fun onStart() {
        super.onStart()
        webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        webView?.onResume()
    }
    override fun onResume() { super.onResume(); updateChrome() }
    override fun onStop() {
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // The activity is no longer visible. Let Chromium waive renderer
            // priority so the system can reclaim renderer memory; onStart()
            // restores foreground priority without clearing page storage.
            webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        }
    }
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
        scriptLoader.close()
        destroyWebView()
        super.onDestroy()
    }
}

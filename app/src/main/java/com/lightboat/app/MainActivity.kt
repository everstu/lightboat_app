package com.lightboat.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
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
import android.text.InputFilter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import com.lightboat.app.databinding.ActivityMainBinding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightboat.app.databinding.DialogBrowserMenuBinding
import com.lightboat.app.databinding.ItemProfileScriptBinding
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject
import com.lightboat.app.userscript.ScriptPreparer
import com.lightboat.app.userscript.UserScriptGroup
import com.lightboat.app.userscript.PreparedScript
import com.lightboat.app.userscript.ScriptInstallationException
import com.lightboat.app.userscript.utf8ByteLength
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ConfigStore
    private lateinit var scriptLoader: BrowserScriptLoader
    private var config = AppConfig.EMPTY
    private var webView: AppWebView? = null
    private var configuredWebView: AppWebView? = null
    private var systemUserAgentMetadata: UserAgentMetadata? = null
    private var mediaProxy: MediaRequestProxy? = null
    private var mediaNetwork: MediaRequestClient? = null
    private val scriptHandlers = mutableListOf<ScriptHandler>()
    // Device tests replace this to exercise the clearCache failure path; the
    // production implementation always clears through the real WebView API.
    @VisibleForTesting
    internal var cacheClearAction: (WebView) -> Unit = { it.clearCache(true) }
    private val worker = Executors.newFixedThreadPool(2)
    private var generation = 0
    private var navigationGeneration = 0L
    private var currentId: String? = null
    private var editingId: String? = null
    private var pageFailed = false
    private var pageReady = false
    private var screen = Screen.PROFILES
    private var exportOptions: com.lightboat.app.backup.BackupOptions? = null
    private var scanTarget: ScanTarget? = null
    private var nativeDialog: Dialog? = null
    private lateinit var defaultUserAgent: String
    private lateinit var appleUserAgent: String
    private lateinit var windowsUserAgent: String
    private var editingPlatform = BrowserPlatform.ANDROID
    private lateinit var fullscreenPlayer: FullscreenPlayer
    private var userScriptHost: UserScriptGroup? = null
    private var draftScripts = emptyList<ProfileScript>()
    private var draftScriptLibrary = emptyList<SharedScript>()
    private var editorDraftId: String? = null
    private val editorDraftStore by lazy { EditorDraftStore(this) }
    private var configRecoveryRequired = false
    private lateinit var profileAdapter: ProfileAdapter
    private lateinit var categoryNameInputFilter: CategoryNameInputFilter
    private lateinit var profileDrag: ItemTouchHelper
    private var profileOrderDirty = false
    private var profileFilter = ProfileFilter()
    private var profileOrderFilter = ProfileFilter()
    private val profileUiPreferences by lazy(LazyThreadSafetyMode.NONE) { getSharedPreferences("profile_ui", MODE_PRIVATE) }
    private var settingsInfoInitialized = false
    private var backupRecoveryBlocked = false
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
        if (uri != null) {
            // The document picker can stop the original dialog and its worker. Recreate
            // the import session so the returned URI always uses a live reader.
            showConfigImport(uri)
        }
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val options = exportOptions ?: com.lightboat.app.backup.BackupOptionsStore(this).load()
        exportOptions = null
        if (uri != null) {
            showNativeDialog(BackupExportDialog(this, defaultUserAgent, options, destination = uri,
                prepareData = ::prepareBackupData, chooseFile = {}))
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
        binding.edgeMenuButton.setOnClickListener { showBrowserMenu() }
        binding.scanWebsiteButton.setOnClickListener { startScan(ScanTarget.WEBSITE) }
        binding.scanScriptButton.setOnClickListener { startScan(ScanTarget.SCRIPT) }
        binding.chooseScripts.setOnClickListener { chooseScripts() }
        binding.addScript.setOnClickListener { addDraftScript() }
        binding.scriptLibrary.setOnClickListener { showScriptLibrary() }
        binding.webDavButton.setOnClickListener { showWebDav() }
        binding.applePlatformButton.setOnClickListener { selectPlatform(BrowserPlatform.APPLE, true) }
        binding.androidPlatformButton.setOnClickListener { selectPlatform(BrowserPlatform.ANDROID, true) }
        binding.windowsPlatformButton.setOnClickListener { selectPlatform(BrowserPlatform.WINDOWS, true) }
        binding.settingsButton.setOnClickListener { showProfiles() }
        binding.editButton.setOnClickListener { showProfiles() }
        binding.refreshButton.setOnClickListener { currentProfile()?.let { openProfile(it) } }
        binding.retryButton.setOnClickListener { currentProfile()?.let { openProfile(it) } ?: launchLast() }
        binding.debugLogButton.setOnClickListener { showScriptLogs() }
        binding.addButton.setOnClickListener { showEditor(null) }
        binding.emptyAddButton.setOnClickListener { showEditor(null) }
        binding.emptyImportButton.setOnClickListener { showConfigImport() }
        binding.resetProfileFilters.setOnClickListener { changeProfileFilter(ProfileFilter()) }
        binding.renameCategories.setOnClickListener { showCategoryRename() }
        categoryNameInputFilter = CategoryNameInputFilter(binding.categoryFilter.paint, {
            val label = binding.categoryFilter
            val available = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
            val preferred = resources.getDimension(R.dimen.category_name_max_width)
            if (available > 0) minOf(preferred, available.toFloat()) else preferred
        }, { config.categories.values })
        binding.categoryInput.filters = arrayOf(InputFilter.LengthFilter(AppConfig.MAX_CATEGORY_NAME), categoryNameInputFilter)
        binding.categoryInput.doAfterTextChanged {
            binding.clearCategory.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            binding.categoryInput.error = null
        }
        binding.clearCategory.setOnClickListener { binding.categoryInput.text?.clear() }
        binding.noteInput.filters = arrayOf(InputFilter.LengthFilter(AppConfig.MAX_PROFILE_NOTE_LENGTH))
        binding.noteInput.doAfterTextChanged {
            binding.noteInput.error = null
            updateNoteCounter()
        }
        updateNoteCounter()
        binding.chooseCategory.setOnClickListener {
            val text = binding.categoryInput.text.toString().trim()
            val selectedId = if (text.isEmpty()) null else config.categories.entries
                .firstOrNull { it.value.equals(text, ignoreCase = true) }?.key ?: ""
            val choices = listOf(CategoryChoice(null, getString(R.string.category_clear))) +
                config.categories.map { (id, name) -> CategoryChoice(id, name) }
            hideKeyboard()
            showCategoryChoices(binding.categoryField, choices, selectedId) { choice ->
                binding.categoryInput.setText(choice.id?.let { config.categories[it] }.orEmpty())
                binding.categoryInput.setSelection(binding.categoryInput.length())
            }
        }
        binding.saveButton.setOnClickListener { saveEditor(false) }
        binding.saveOpenButton.setOnClickListener { saveEditor(true) }
        binding.cancelButton.setOnClickListener { showProfiles() }
        binding.returnButton.setOnClickListener { leaveProfileManager() }
        binding.importButton.setOnClickListener { showConfigImport() }
        binding.exportButton.setOnClickListener {
            commitProfileOrder()
            hideKeyboard()
            showNativeDialog(BackupExportDialog(this, defaultUserAgent, com.lightboat.app.backup.BackupOptionsStore(this).load(),
                prepareData = ::prepareBackupData, chooseFile = { options ->
                    exportOptions = options
                    exportFile.launch("lightboat-backup.zip")
                }))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backupRecoveryBlocked) { finish(); return }
                if (configRecoveryRequired) { confirmExit(); return }
                if (fullscreenPlayer.isActive) { fullscreenPlayer.hide(); return }
                when (screen) {
                    Screen.EDITOR -> showProfiles()
                    Screen.PROFILES -> leaveProfileManager()
                    Screen.LOADING, Screen.ERROR -> showProfiles()
                    Screen.BROWSER -> if (webView?.canGoBack() == true) webView?.goBack() else confirmExit()
                }
            }
        })
        if (com.lightboat.app.backup.BackupRepository.isRestoring) {
            backupRecoveryBlocked = true
            binding.settingsButton.isEnabled = false
            showLoading(R.string.webdav_restoring)
            worker.execute {
                val result = runCatching { com.lightboat.app.backup.BackupRepository.awaitRestore() }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        if (result.isSuccess) {
                            backupRecoveryBlocked = false
                            binding.settingsButton.isEnabled = true
                            finishStartup(savedInstanceState)
                        } else blockForBackupRecovery()
                    }
                }
            }
        } else finishStartup(savedInstanceState)
    }

    private fun finishStartup(savedInstanceState: Bundle?) {
        try {
            com.lightboat.app.backup.BackupRepository.recoverPending(this)?.let { retired -> worker.execute { discardBackupFiles(listOf(retired)) } }
            val abandonedBackups = com.lightboat.app.backup.BackupRepository.abandonedFiles(this)
            worker.execute { discardBackupFiles(abandonedBackups) }
        } catch (error: Exception) {
            DebugLog.event("webdav", "startup recovery failed type=${error.javaClass.simpleName}")
            blockForBackupRecovery()
            return
        }
        config = try { store.load() } catch (error: Exception) {
            DebugLog.event("config", "load failed (${error.javaClass.simpleName}); recovery required")
            configRecoveryRequired = true
            showConfigRecovery()
            return
        }
        val categoryId = if (savedInstanceState?.containsKey("profileCategoryFilter") == true)
            savedInstanceState.getString("profileCategoryFilter") else profileUiPreferences.getString("categoryId", null)
        val favoritesOnly = if (savedInstanceState?.containsKey("profileFavoriteFilter") == true)
            savedInstanceState.getBoolean("profileFavoriteFilter") else profileUiPreferences.getBoolean("favoritesOnly", false)
        rememberProfileFilter(ProfileFilter(categoryId, favoritesOnly))
        if (savedInstanceState?.getBoolean("pendingBackupExport") == true) exportOptions = com.lightboat.app.backup.BackupOptions(
            savedInstanceState.getBoolean("exportScriptData", true), savedInstanceState.getBoolean("exportPreferences", true))
        scanTarget = savedInstanceState?.getString("scanTarget")?.let { value ->
            ScanTarget.entries.firstOrNull { it.name == value }
        }
        binding.edgeMenuButton.restoreAnchor(savedInstanceState?.getBoolean("menuLeft") ?: false,
            savedInstanceState?.getFloat("menuHeight", 0.68f) ?: 0.68f)
        if (savedInstanceState?.getBoolean("editor") == true) {
            showEditor(config.profiles.firstOrNull { it.id == savedInstanceState.getString("editingId") })
            binding.nameInput.setText(savedInstanceState.getString("draftName"))
            binding.categoryInput.setText(savedInstanceState.getString("draftCategory").orEmpty())
            binding.favoriteInput.isChecked = savedInstanceState.getBoolean("draftFavorite", binding.favoriteInput.isChecked)
            binding.websiteInput.setText(savedInstanceState.getString("draftUrl"))
            binding.scriptInput.setText(savedInstanceState.getString("draftScript"))
            savedInstanceState.getString("draftScriptLibrary")?.let { text ->
                runCatching { ConfigCodec.decode(text) }.getOrNull()?.let { draftScriptLibrary = it.scriptLibrary }
            }
            savedInstanceState.getString("draftScripts")?.let { text ->
                draftScripts = runCatching { ConfigCodec.decodeReferences(JSONArray(text), draftScriptLibrary) }.getOrDefault(emptyList())
            }
            savedInstanceState.getString("editorDraftId")?.let { id ->
                runCatching { editorDraftStore.load(id) }.onSuccess { (library, references) ->
                    editorDraftId = id; draftScriptLibrary = library; draftScripts = references
                }.onFailure { toast(getString(R.string.editor_draft_read_error)) }
            }
            renderDraftScripts()
            binding.userAgentInput.setText(savedInstanceState.getString("draftUserAgent", defaultUserAgent))
            savedInstanceState.getString("draftPlatform")?.let {
                selectPlatform(BrowserPlatform.parse(it), false)
            }
            savedInstanceState.getString("draftNote")?.let { binding.noteInput.setText(it) }
            updateNoteCounter()
        } else if (savedInstanceState?.getBoolean("manager") == true) showProfiles() else launchLast()
    }

    private fun discardBackupFiles(files: List<java.io.File>) {
        files.forEach { file ->
            // Activity recreation can overlap an already scheduled cleanup of
            // the same retired directory. Cleanup must never crash the process.
            runCatching { file.deleteRecursively() }.onFailure {
                DebugLog.event("webdav", "temporary backup cleanup deferred (${it.javaClass.simpleName})")
            }
        }
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
        binding.titleBar.visibility = if (next == Screen.BROWSER || next == Screen.PROFILES || next == Screen.EDITOR) View.GONE else View.VISIBLE
        binding.menuLayer.visibility = if (next == Screen.BROWSER) View.VISIBLE else View.GONE
        if (next == Screen.BROWSER) binding.edgeMenuButton.dock()
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
        if (isFinishing || isDestroyed) return
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
        val commandHost = userScriptHost
        val updateCommands: () -> Unit = {
            menu.menuScriptCommands.isEnabled = userScriptHost === commandHost && commandHost?.menus?.isNotEmpty() == true
            menu.menuScriptCommands.alpha = if (menu.menuScriptCommands.isEnabled) 1f else 0.45f
        }
        val dialog = object : Dialog(this), ResponsiveDialog {
            override fun onStart() {
                super.onStart()
                commandHost?.addCommandListener(updateCommands)
                updateCommands()
            }

            override fun onStop() {
                commandHost?.removeCommandListener(updateCommands)
                super.onStop()
            }
            override fun updateLayout() {
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setGravity(Gravity.BOTTOM)
                    attributes = attributes.apply { y = DialogLayout.dp(this@MainActivity, 12) }
                    setLayout(DialogLayout.width(this@MainActivity, maxWidthDp = 560, marginDp = 32),
                        WindowManager.LayoutParams.WRAP_CONTENT)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0.38f)
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(menu.root)
        val profile = currentProfile()
        menu.menuProfileName.text = profile?.name ?: getString(R.string.app_name)
        menu.menuProfileHost.text = profile?.let { Uri.parse(it.url).authority }.orEmpty()
        ViewCompat.setAccessibilityHeading(menu.menuProfileName, true)
        menu.menuSwitch.setOnClickListener { dialog.dismiss(); showProfilePicker() }
        menu.menuScan.setOnClickListener { dialog.dismiss(); startScan(ScanTarget.COPY) }
        menu.menuRefresh.setOnClickListener { dialog.dismiss(); currentProfile()?.let { openProfile(it) } }
        menu.menuSettings.setOnClickListener { dialog.dismiss(); showProfiles() }
        menu.menuScriptLogs.setOnClickListener { dialog.dismiss(); showScriptLogs() }
        updateCommands()
        menu.menuScriptCommands.setOnClickListener { dialog.dismiss(); showScriptCommands() }
        menu.menuClose.setOnClickListener { dialog.dismiss() }
        dialog.setCanceledOnTouchOutside(true)
        showNativeDialog(dialog)
        dialog.updateLayout()
    }

    private fun showScriptLogs() {
        showNativeDialog(DebugLogDialog(this))
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
        if (config.profiles.isEmpty()) { showProfiles(); return }
        showNativeDialog(ProfileSwitchDialog(this, config, currentId, ::openProfile,
            profileFilter, ::rememberProfileFilter))
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
        (nativeDialog as? ScriptFailureDialog)?.dismiss()
        DebugLog.event("script", "cancel generation=$generation")
        scriptLoader.cancel()
    }

    private fun showProfiles() {
        if (configRecoveryRequired) { showConfigRecovery(); return }
        editorDraftStore.delete(editorDraftId); editorDraftId = null
        commitProfileOrder()
        cancelDownload()
        scriptLoader.close()
        ensureSettingsUi()
        hideKeyboard()
        showScreen(Screen.PROFILES)
        renderProfiles()
    }

    private fun changeProfileFilter(next: ProfileFilter) {
        commitProfileOrder()
        rememberProfileFilter(next)
        renderProfiles()
        binding.profilesList.scrollToPosition(0)
    }

    private fun rememberProfileFilter(next: ProfileFilter) {
        profileFilter = next.resolve(config)
        if (profileUiPreferences.getString("categoryId", null) != profileFilter.categoryId ||
            profileUiPreferences.getBoolean("favoritesOnly", false) != profileFilter.favoritesOnly) {
            profileUiPreferences.edit().putString("categoryId", profileFilter.categoryId)
                .putBoolean("favoritesOnly", profileFilter.favoritesOnly).apply()
        }
    }

    private fun renderProfiles() {
        profileFilter = profileFilter.resolve(config)
        val visible = profileFilter.apply(config)
        bindProfileCategoryFilter(binding.categoryFilter, config, profileFilter, ::changeProfileFilter)
        bindProfileFavoriteFilter(binding.favoriteFilter, profileFilter, ::changeProfileFilter)
        binding.profileFilters.visibility = if (config.profiles.isEmpty()) View.GONE else View.VISIBLE
        binding.renameCategories.visibility = if (config.categories.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyProfiles.visibility = if (config.profiles.isEmpty()) View.VISIBLE else View.GONE
        binding.profilesSection.visibility = if (config.profiles.isEmpty()) View.GONE else View.VISIBLE
        binding.profilesList.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyFilteredProfiles.visibility = if (config.profiles.isNotEmpty() && visible.isEmpty()) View.VISIBLE else View.GONE
        binding.profileCount.text = if (profileFilter == ProfileFilter()) getString(R.string.profiles_count, config.profiles.size)
            else getString(R.string.profiles_filtered_count, visible.size, config.profiles.size)
        binding.returnButton.visibility = View.VISIBLE
        binding.exportButton.isEnabled = config.profiles.isNotEmpty()
        binding.exportButton.alpha = if (binding.exportButton.isEnabled) 1f else 0.4f
        binding.addButton.visibility = if (config.profiles.isEmpty()) View.GONE else View.VISIBLE
        binding.addButton.isEnabled = config.profiles.size < AppConfig.MAX_PROFILES
        binding.addButton.alpha = if (binding.addButton.isEnabled) 1f else 0.4f
        binding.addButton.contentDescription = if (binding.addButton.isEnabled) getString(R.string.add_profile)
            else getString(R.string.profile_limit, AppConfig.MAX_PROFILES)
        profileOrderDirty = false
        profileOrderFilter = profileFilter
        profileAdapter.submit(config, currentId.takeIf { webView != null }, visible)
    }

    private fun showCategoryRename() {
        commitProfileOrder()
        if (config.categories.isEmpty()) return
        showNativeDialog(CategoryRenameDialog(this, config, profileFilter.categoryId, categoryNameInputFilter) { next ->
            if (!persist(next)) false else {
                renderProfiles()
                toast(getString(R.string.category_renamed))
                true
            }
        })
    }

    private fun setupProfileList() {
        profileAdapter = ProfileAdapter(::openProfile, ::showEditor, ::confirmDeleteProfile, ::toggleProfileFavorite,
            ::moveProfileToTop, ::showProfileNote)
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
        val visibleIds = profileOrderFilter.apply(config).map { it.id }
        if (ids == visibleIds) return
        // A configuration import/delete can invalidate an old drag animation's callback.
        if (ids.size != visibleIds.size || ids.toSet() != visibleIds.toSet()) return
        if (!persist(config.reorderVisible(ids, profileOrderFilter))) renderProfiles()
    }

    private fun toggleProfileFavorite(profile: Profile) {
        commitProfileOrder()
        if (config.profiles.none { it.id == profile.id }) return
        if (persist(config.toggleFavorite(profile.id))) renderProfiles()
    }

    private fun moveProfileToTop(profile: Profile) {
        commitProfileOrder()
        if (config.profiles.none { it.id == profile.id }) return
        if (persist(config.moveToTop(profile.id))) {
            renderProfiles()
            binding.profilesList.scrollToPosition(0)
            toast(getString(R.string.profile_moved_top))
        }
    }

    private fun confirmDeleteProfile(profile: Profile) {
        commitProfileOrder()
        showNativeDialog(AlertDialog.Builder(this).setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, profile.name))
            .setNegativeButton(R.string.import_cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (persist(config.remove(profile.id))) {
                    if (currentId == profile.id) { destroyWebView(); currentId = null }
                    showProfiles()
                }
            }.create())
    }

    private fun showProfileNote(profile: Profile) {
        hideKeyboard()
        showNativeDialog(ProfileNoteDialog(this, profile.name, profile.note) { note ->
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.profile_note_title), note))
            if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.profile_note_copied))
        })
    }

    private fun showEditor(profile: Profile?) {
        if (profile == null && config.profiles.size >= AppConfig.MAX_PROFILES) {
            toast(getString(R.string.profile_limit, AppConfig.MAX_PROFILES)); return
        }
        editorDraftStore.delete(editorDraftId); editorDraftId = null
        editingId = profile?.id
        showScreen(Screen.EDITOR)
        binding.configTitle.setText(if (profile == null) R.string.setup_title else R.string.edit_profile)
        binding.configDescription.setText(if (profile == null) R.string.setup_description else R.string.edit_profile_description)
        binding.nameInput.setText(profile?.name.orEmpty())
        val categoryId = profile?.categoryId ?: if (profile == null) profileFilter.categoryId else null
        binding.categoryInput.setText(config.categories[categoryId].orEmpty())
        binding.categoryInput.error = null
        binding.favoriteInput.isChecked = profile?.favorite ?: false
        binding.websiteInput.setText(profile?.url.orEmpty())
        binding.scriptInput.setText("")
        draftScripts = profile?.scripts.orEmpty().toList()
        draftScriptLibrary = config.scriptLibrary.toList()
        renderDraftScripts()
        binding.userAgentInput.setText(profile?.userAgent ?: defaultUserAgent)
        selectPlatform(profile?.platform ?: BrowserPlatform.ANDROID, false)
        binding.noteInput.setText(profile?.note.orEmpty())
        binding.noteInput.error = null
        binding.nameInput.error = null
        binding.websiteInput.error = null
        binding.scriptInput.error = null
        binding.userAgentInput.error = null
        binding.saveButton.setText(R.string.save_profile)
        binding.saveOpenButton.setText(R.string.save_open)
        binding.cancelButton.visibility = View.VISIBLE
        binding.configPanel.clearFocus()
        binding.editorScroll.post { binding.editorScroll.scrollTo(0, 0) }
        updateNoteCounter()
    }

    private fun updateNoteCounter() {
        if (!::binding.isInitialized) return
        val length = binding.noteInput.length()
        val limit = AppConfig.MAX_PROFILE_NOTE_LENGTH
        val reached = length >= limit
        binding.noteCounter.setText(
            if (reached) getString(R.string.profile_note_limit_reached, limit)
            else getString(R.string.profile_note_length, length, limit)
        )
        binding.noteCounter.setTextColor(getColor(if (reached) R.color.accent else R.color.muted))
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
            button.alpha = 1f
        }
    }

    private fun saveEditor(openAfterSave: Boolean) {
        val name = binding.nameInput.text.toString().trim()
        if (name.isBlank()) { binding.nameInput.error = getString(R.string.profile_name_required); binding.nameInput.requestFocus(); return }
        val url = try { AppConfig.normalizeUrl(binding.websiteInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.websiteInput.error = error.message; binding.websiteInput.requestFocus(); return
        }
        if (binding.scriptInput.text.isNotBlank() && !addDraftScript()) return
        val userAgent = try { AppConfig.normalizeUserAgent(binding.userAgentInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.userAgentInput.error = error.message; binding.userAgentInput.requestFocus(); return
        }
        val categoryName = try { AppConfig.normalizeCategoryName(binding.categoryInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.categoryInput.error = error.message; binding.categoryInput.requestFocus(); return
        }
        if (!categoryNameInputFilter.fits(categoryName)) {
            binding.categoryInput.error = getString(R.string.category_name_too_wide)
            binding.categoryInput.requestFocus(); return
        }
        val note = try { AppConfig.normalizeProfileNote(binding.noteInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.noteInput.error = error.message; binding.noteInput.requestFocus(); return
        }
        val previous = config.profiles.firstOrNull { it.id == editingId }
        val profile = Profile(
            id = editingId ?: UUID.randomUUID().toString(),
            name = name,
            url = url,
            scripts = draftScripts,
            userAgent = userAgent,
            platform = editingPlatform,
            categoryId = previous?.categoryId,
            favorite = binding.favoriteInput.isChecked,
            note = note,
        )
        val next = try {
            config.replaceScriptLibrary(draftScriptLibrary).upsert(profile, categoryName).let { if (openAfterSave) it.select(profile.id) else it }
        } catch (error: Exception) {
            toast(error.message.orEmpty()); return
        }
        if (persist(next)) {
            editorDraftStore.delete(editorDraftId); editorDraftId = null
            hideKeyboard()
            if (openAfterSave) openProfile(next.profiles.first { it.id == profile.id }) else {
                showProfiles()
                toast(getString(R.string.profile_saved))
            }
        }
    }

    private fun persist(next: AppConfig): Boolean = try {
        store.save(next)
        config = next
        configRecoveryRequired = false
        true
    } catch (error: Exception) {
        toast(getString(R.string.operation_error, error.message.orEmpty()))
        false
    }

    private fun renderDraftScripts() {
        binding.selectedScriptsSummary.text = if (draftScripts.isEmpty()) getString(R.string.script_none_selected)
            else getString(R.string.script_selection_summary, draftScripts.size, draftScripts.count { it.enabled })
        binding.selectedScriptsHelp.setText(if (draftScripts.isEmpty()) R.string.script_selected_empty_help else R.string.script_selected_help)
        binding.selectedScripts.visibility = if (draftScripts.isEmpty()) View.GONE else View.VISIBLE
        binding.selectedScripts.removeAllViews()
        draftScripts.forEachIndexed { index, ref ->
            val script = draftScriptLibrary.firstOrNull { it.id == ref.scriptId } ?: return@forEachIndexed
            val row = ItemProfileScriptBinding.inflate(layoutInflater, binding.selectedScripts, false)
            row.root.tag = script.id
            row.selectedScriptOrder.text = (index + 1).toString()
            row.selectedScriptName.text = script.name
            row.selectedScriptUrl.text = script.url.substringBefore('?').substringBefore('#')
            row.enableSelectedScript.isChecked = ref.enabled
            row.enableSelectedScript.contentDescription = getString(R.string.script_enable_description, script.name)
            fun showEnabled(enabled: Boolean) {
                row.root.isActivated = enabled
                row.selectedScriptOrder.alpha = if (enabled) 1f else 0.45f
                row.selectedScriptName.setTextColor(getColor(if (enabled) R.color.ink else R.color.muted))
                ViewCompat.setStateDescription(row.enableSelectedScript, getString(if (enabled) R.string.script_enabled else R.string.script_disabled))
            }
            showEnabled(ref.enabled)
            row.enableSelectedScript.setOnCheckedChangeListener { _, enabled ->
                val current = draftScripts.indexOfFirst { it.scriptId == script.id }
                if (current >= 0) draftScripts = draftScripts.toMutableList().apply { this[current] = this[current].copy(enabled = enabled) }
                showEnabled(enabled)
                binding.selectedScriptsSummary.text = getString(R.string.script_selection_summary, draftScripts.size, draftScripts.count { it.enabled })
            }
            row.selectedScriptUp.isEnabled = index > 0
            row.selectedScriptDown.isEnabled = index < draftScripts.lastIndex
            row.selectedScriptUp.alpha = if (row.selectedScriptUp.isEnabled) 1f else 0.35f
            row.selectedScriptDown.alpha = if (row.selectedScriptDown.isEnabled) 1f else 0.35f
            row.selectedScriptUp.contentDescription = getString(R.string.script_move_up_description, script.name)
            row.selectedScriptDown.contentDescription = getString(R.string.script_move_down_description, script.name)
            fun move(offset: Int) {
                val current = draftScripts.indexOfFirst { it.scriptId == script.id }
                if (current >= 0 && current + offset in draftScripts.indices) {
                    draftScripts = draftScripts.toMutableList().apply { add(current + offset, removeAt(current)) }
                    renderDraftScripts()
                }
            }
            row.selectedScriptUp.setOnClickListener { move(-1) }
            row.selectedScriptDown.setOnClickListener { move(1) }
            row.selectedScriptUp.tooltipText = getString(R.string.script_up)
            row.selectedScriptDown.tooltipText = getString(R.string.script_down)
            row.removeSelectedScript.contentDescription = getString(R.string.script_remove_description, script.name)
            row.removeSelectedScript.tooltipText = getString(R.string.script_remove_selection)
            row.removeSelectedScript.setOnClickListener {
                draftScripts = draftScripts.filterNot { it.scriptId == script.id }
                renderDraftScripts()
            }
            binding.selectedScripts.addView(row.root)
        }
    }

    private fun addDraftScript(): Boolean {
        val url = try { AppConfig.normalizeScriptUrl(binding.scriptInput.text.toString()) } catch (error: IllegalArgumentException) {
            binding.scriptInput.error = error.message; binding.scriptInput.requestFocus(); return false
        }
        val existing = draftScriptLibrary.firstOrNull { it.url == url }
        if (draftScripts.none { it.scriptId == existing?.id } && draftScripts.size >= AppConfig.MAX_PROFILE_SCRIPTS) {
            binding.scriptInput.error = getString(R.string.script_profile_limit, AppConfig.MAX_PROFILE_SCRIPTS); return false
        }
        val script = existing ?: SharedScript(UUID.randomUUID().toString(),
            AppConfig.defaultScriptName(url), url)
        try { draftScriptLibrary = AppConfig.upsertScript(draftScriptLibrary, script) }
        catch (error: IllegalArgumentException) { binding.scriptInput.error = error.message; return false }
        if (draftScripts.none { it.scriptId == script.id }) draftScripts += ProfileScript(script.id)
        binding.scriptInput.setText("")
        binding.scriptInput.error = null
        renderDraftScripts()
        return true
    }

    private fun chooseScripts() {
        hideKeyboard()
        showNativeDialog(ScriptLibraryDialog(this, config.copy(scriptLibrary = draftScriptLibrary), draftScripts) { library, references ->
            draftScriptLibrary = library; draftScripts = references; renderDraftScripts(); true
        })
    }

    private fun showScriptLibrary() {
        commitProfileOrder()
        showNativeDialog(ScriptLibraryDialog(this, config, null) { library, _ ->
            if (persist(config.replaceScriptLibrary(library))) { renderProfiles(); true } else false
        })
    }

    private fun openProfile(profile: Profile) = openProfile(profile, emptySet())

    private fun openProfile(profile: Profile, skippedScriptIds: Set<String>, clearWebCache: Boolean = false) {
        DebugLog.event("profile", "open name=${profile.name.take(80)} url=${DebugLog.safeUrl(profile.url)} scripts=${profile.scripts.count { it.enabled }} platform=${profile.platform}")
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
        // A cache clear belongs to this one explicit recovery attempt. It is a
        // local pending flag, never persisted and never inherited by a later
        // refresh or profile switch.
        var cacheClearPending = clearWebCache
        showLoading(R.string.preparing)
        DebugLog.event("profile", "new session navigation=$navigationRequest generation=$generation")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showError(R.string.unsupported_title, getString(R.string.unsupported_message))
            return
        }
        val request = generation
        val scripts = config.activeScripts(profile).filterNot { it.id in skippedScriptIds }
        val prepared = mutableListOf<PreparedScript>()
        val failures = mutableListOf<Pair<SharedScript, Throwable>>()
        var totalBytes = 0
        fun current() = !isDestroyed && request == generation && navigationRequest == navigationGeneration
        fun fail(script: SharedScript, error: Throwable) {
            if (!current()) return
            failures += script to error
            DebugLog.event("script", "prepare failed name=${script.name} error=${error.message.orEmpty().take(240)}")
        }
        fun openPrepared() {
            scriptLoader.close()
            binding.root.post {
                if (current()) {
                    try {
                        showLoading(R.string.loading_page)
                        prepareMainWebView(profile)
                        if (cacheClearPending) {
                            // clearCache has no completion callback. Run it on
                            // this brand-new, still-blank WebView's UI thread,
                            // then continue with the normal install/load flow.
                            val recoveryView = checkNotNull(webView)
                            try {
                                cacheClearAction(recoveryView)
                                cacheClearPending = false
                                DebugLog.event("cache-recovery", "clear requested includeDiskFiles=true scope=application")
                            } catch (error: Exception) {
                                DebugLog.event("cache-recovery", "clear failed ${error.message.orEmpty().take(240)}")
                                destroyWebView()
                                showCacheClearFailed(profile.id)
                                return@post
                            }
                        }
                        createWebView(profile, nativeGuardScript, prepared.toList(), navigationRequest)
                    } catch (error: ScriptInstallationException) {
                        if (!current()) return@post
                        DebugLog.event("script", "install failed name=${error.script.name} error=${error.message.orEmpty().take(240)}")
                        destroyWebView()
                        val failedNavigation = navigationGeneration
                        fun stillCurrent() = !isDestroyed && request == generation && failedNavigation == navigationGeneration
                        showError(R.string.script_error_title, "${error.script.name}：${error.message.orEmpty()}")
                        showNativeDialog(ScriptFailureDialog(this, false,
                            proceed = { if (stillCurrent()) openProfile(profile, skippedScriptIds + failures.map { it.first.id } + error.script.id, cacheClearPending) },
                            retry = { if (stillCurrent()) openProfile(profile, skippedScriptIds, cacheClearPending) },
                            cancelVisit = { if (stillCurrent()) showProfiles() }
                        ).apply { addFailure(error.script, error.message ?: getString(R.string.script_download_error)) })
                    } catch (error: Exception) {
                        DebugLog.event("webview", "create failed ${error.message.orEmpty().take(240)}")
                        destroyWebView()
                        showError(R.string.page_error_title, error.message ?: getString(R.string.script_download_error))
                    }
                }
            }
        }
        fun loadNext(index: Int) {
            if (!current()) return
            if (index == scripts.size) {
                scriptLoader.close()
                if (failures.isEmpty()) openPrepared() else {
                    showError(R.string.script_error_title, failures.joinToString("\n") { (script, error) -> "${script.name}：${error.message.orEmpty()}" })
                    showNativeDialog(ScriptFailureDialog(this, false,
                        proceed = { if (current()) openPrepared() },
                        retry = { if (current()) openProfile(profile, skippedScriptIds, cacheClearPending) },
                        cancelVisit = { if (current()) showProfiles() }
                    ).apply { failures.forEach { (script, error) -> addFailure(script, error.message ?: getString(R.string.script_download_error)) } })
                }
                return
            }
            val script = scripts[index]
            binding.statusDetail.text = getString(R.string.script_preparing_item, index + 1, scripts.size, script.name)
            try {
                scriptLoader.load(script.url, profile.userAgent, profile.platform) { result ->
                    if (!current()) return@load
                    result.fold(onSuccess = { source ->
                        try {
                            ScriptPreparer(scriptLoader).prepare(
                                source, script.url, profile.userAgent, profile.platform
                            ) { resultBundle ->
                                if (!current()) return@prepare
                                resultBundle.fold(onSuccess = { bundle ->
                                    val bundleBytes = utf8ByteLength(bundle.source) +
                                        bundle.requirements.sumOf(::utf8ByteLength) + utf8ByteLength(bundle.resources.toString())
                                    if (totalBytes + bundleBytes > 16 * 1024 * 1024) {
                                        fail(script, IllegalArgumentException("当前配置的脚本及依赖合计不能超过 16 MiB"))
                                    } else {
                                        totalBytes += bundleBytes
                                        prepared += PreparedScript(script, bundle)
                                        DebugLog.event("script", "prepared ${index + 1}/${scripts.size} name=${script.name} runAt=${bundle.metadata.runAt}")
                                    }
                                    loadNext(index + 1)
                                }, onFailure = { fail(script, it); loadNext(index + 1) })
                            }
                        } catch (error: Exception) { fail(script, error); loadNext(index + 1) }
                    }, onFailure = { fail(script, it); loadNext(index + 1) })
                }
            } catch (error: Exception) { fail(script, error); loadNext(index + 1) }
        }
        loadNext(0)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(profile: Profile, guard: String, bundles: List<PreparedScript>, navigationRequest: Long) {
        DebugLog.event("webview", "create navigation=$navigationRequest url=${DebugLog.safeUrl(profile.url)}")
        val view = webView ?: AppWebView(this).also {
            webView = it
            binding.webContainer.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        fun isCurrentNavigation() = view === webView && navigationRequest == navigationGeneration
        var mainFrameUrl = profile.url
        fun isMainFrameUrl(address: String): Boolean {
            val failed = runCatching { URI(address.substringBefore('#')).normalize() }.getOrNull() ?: return false
            return listOf(mainFrameUrl, view.url).any { candidate ->
                candidate != null && runCatching { URI(candidate.substringBefore('#')).normalize() == failed }.getOrDefault(false)
            }
        }
        val cookies = CookieManager.getInstance()
        val network = MediaRequestClient(profile.userAgent,
            cookies = { cookies.getCookie(it) }, saveCookie = { url, cookie -> cookies.setCookie(url, cookie) },
            addresses = MediaAddressPolicy(profile.url))
        mediaNetwork = network
        val proxy = MediaRequestProxy(network)
        mediaProxy = proxy
        proxy.start()
        DebugLog.event("network", "media element proxy enabled; Chromium fetch/XHR/MSE unchanged; requestReplay=false")
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (isCurrentNavigation()) {
                    val host = userScriptHost
                    if (host != null) host.console(message)
                    else if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        DebugLog.event("web-console", "${message.message().take(1000)} (${message.sourceId()}:${message.lineNumber()})")
                    }
                }
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
            // Observe requests without replacing Chromium's response or transport.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (DebugLog.shouldTraceUrl(request.url.toString())) {
                    DebugLog.event("web-request", "${request.method} ${DebugLog.safeUrl(request.url.toString())} native=true")
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!isCurrentNavigation()) return true
                if (request.url.scheme?.lowercase() in setOf("http", "https")) {
                    if (request.isForMainFrame) mainFrameUrl = request.url.toString()
                    return false
                }
                if (request.isForMainFrame) toast(getString(R.string.unsafe_link))
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!isCurrentNavigation()) return
                DebugLog.event("page", "started ${DebugLog.safeUrl(url)}")
                if (url != null) mainFrameUrl = url
                // Document-start bridge messages can arrive before this callback.
                // Each document disposes only its own session on pagehide; a late
                // navigation callback must never clear a newer document's handshake.
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
                if (request.isForMainFrame && isCurrentNavigation()) {
                    // Only a main-frame redirect loop can be caused by this
                    // app's shared WebView HTTP cache; other failures keep the
                    // ordinary retry/edit entry points.
                    failPage(if (error.errorCode == ERROR_FAILED_SSL_HANDSHAKE) getString(R.string.bad_certificate)
                        else error.description.toString(),
                        allowCacheRecovery = error.errorCode == ERROR_REDIRECT_LOOP)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                DebugLog.event("http-error", "${request.method} ${DebugLog.safeUrl(request.url.toString())} status=${response.statusCode}")
                if (request.isForMainFrame && isCurrentNavigation()) {
                    // HTTP errors can contain a working verification/login page.
                    // Let Chromium display it and keep its redirects, scripts and cookies alive.
                    DebugLog.event("page", "showing HTTP ${response.statusCode} response in browser")
                    revealPage(view)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (isCurrentNavigation()) {
                    // This also reports images/iframes. Some WebViews do not send
                    // onReceivedError after cancel(), so identify the main request.
                    DebugLog.event("tls-error", "blocked certificate code=${error.primaryError} url=${DebugLog.safeUrl(error.url)}")
                    if (isMainFrameUrl(error.url)) failPage(getString(R.string.bad_certificate))
                }
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
        var runtimeFailureDialog: ScriptFailureDialog? = null
        userScriptHost = if (bundles.isEmpty()) null else UserScriptGroup(this, view, profile, bundles, config) { script, message ->
            binding.root.post {
                if (!isCurrentNavigation() || pageFailed || screen != Screen.BROWSER) return@post
                val dialog = runtimeFailureDialog?.takeIf { it.isShowing } ?: ScriptFailureDialog(this, true,
                    proceed = {},
                    retry = { if (isCurrentNavigation()) openProfile(profile) },
                    cancelVisit = { if (isCurrentNavigation()) { destroyWebView(); showProfiles() } }
                ).also { runtimeFailureDialog = it; showNativeDialog(it) }
                dialog.addFailure(script, message)
            }
        }
        userScriptHost?.let { scriptHandlers += it.install() }
        view.onResume()
        // Browser requests use WebView identity; direct media uses the profile proxy.
        applyProfileUserAgent(view, profile)
        // Scripts are registered; show the browser now so loading and verification
        // remain interactive without waiting for a commit or a finished document.
        revealPage(view)
        updatePageProgress(0, true)
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
                // Direct native media uses a loopback HTTP source; JS requests remain Chromium-owned.
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                mediaPlaybackRequiresUserGesture = true
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

    private fun failPage(message: String, allowCacheRecovery: Boolean = false) {
        DebugLog.event("page-error", "main frame failed ${message.take(300)}")
        userScriptHost?.resetDocuments()
        fullscreenPlayer.hide()
        pageFailed = true
        pageReady = false
        webView?.stopLoading()
        // Only the failed profile can offer cache recovery. Capture its ID now:
        // the posted teardown below destroys the failed WebView and advances
        // navigationGeneration, so the error page must not rely on either.
        val cacheRecoveryProfileId = if (allowCacheRecovery) currentId else null
        val failedView = webView
        val failedNavigation = navigationGeneration
        // Leave the WebView callback before destroying its renderer and media session.
        binding.root.post {
            if (!isDestroyed && pageFailed && webView === failedView && navigationGeneration == failedNavigation) {
                destroyWebView()
            }
        }
        if (screen in listOf(Screen.BROWSER, Screen.LOADING, Screen.ERROR)) {
            showError(R.string.page_error_title, message, cacheRecoveryProfileId)
        }
    }

    private fun returnToPage() {
        if (webView != null && pageReady) showScreen(Screen.BROWSER) else launchLast()
    }

    private fun leaveProfileManager() {
        if ((webView != null && pageReady) || config.lastProfile != null) returnToPage() else confirmExit()
    }

    private fun showLoading(title: Int) {
        showScreen(Screen.LOADING)
        binding.statusTitle.setText(title)
        binding.statusDetail.setText(R.string.preparing_note)
        binding.statusSpinner.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.editButton.visibility = View.GONE
        resetCacheRecoveryButton()
    }

    private fun showError(title: Int, message: String, cacheRecoveryProfileId: String? = null) {
        DebugLog.event("ui-error", "${getString(title)} ${message.take(300)}")
        showScreen(Screen.ERROR)
        binding.statusTitle.setText(title)
        binding.statusDetail.text = if (cacheRecoveryProfileId == null) message else listOf(
            message,
            getString(R.string.redirect_loop_recovery_hint),
            getString(R.string.redirect_loop_recovery_scope)
        ).joinToString("\n\n")
        binding.statusSpinner.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.editButton.visibility = View.VISIBLE
        binding.retryButton.setText(R.string.retry)
        binding.retryButton.setOnClickListener { currentProfile()?.let { openProfile(it) } ?: launchLast() }
        binding.editButton.setText(R.string.edit_addresses)
        binding.editButton.setOnClickListener { showProfiles() }
        // Every error page starts without the recovery entry; a stale click
        // listener from an earlier redirect loop must never act on this page.
        resetCacheRecoveryButton()
        if (cacheRecoveryProfileId != null) {
            binding.clearWebCacheRetryButton.visibility = View.VISIBLE
            binding.clearWebCacheRetryButton.setOnClickListener { retryAfterClearingWebCache(cacheRecoveryProfileId) }
        }
        updatePageProgress(binding.pageProgress.progress, false)
    }

    private fun resetCacheRecoveryButton() {
        binding.clearWebCacheRetryButton.apply {
            visibility = View.GONE
            isEnabled = true
            setOnClickListener(null)
        }
    }

    /** Runs one explicit cache clear followed by a normal reopen of [profileId]. */
    private fun retryAfterClearingWebCache(profileId: String) {
        val button = binding.clearWebCacheRetryButton
        // showLoading hides the button as soon as the reopen starts. This guard
        // blocks a second tap if the reopen is refused synchronously instead.
        if (!button.isEnabled || screen != Screen.ERROR) return
        if (currentId != profileId) {
            // Another profile is now current; this error page is stale.
            resetCacheRecoveryButton()
            return
        }
        val profile = config.profiles.firstOrNull { it.id == profileId }
        if (profile == null) {
            // The failed profile was deleted or replaced while this page stayed up.
            resetCacheRecoveryButton()
            return
        }
        button.isEnabled = false
        DebugLog.event("cache-recovery",
            "requested profile=${profile.id.take(80)} navigation=$navigationGeneration url=${DebugLog.safeUrl(profile.url)}")
        openProfile(profile, emptySet(), clearWebCache = true)
        // A synchronous refusal (for example a failed config save) leaves this
        // error page in place; keep the explicit entry operable in that case.
        if (screen == Screen.ERROR) button.isEnabled = true
    }

    /**
     * The clear failed before the new page loaded. Its retry must repeat the
     * explicit recovery: a plain reopen would still see the cached redirect.
     */
    private fun showCacheClearFailed(profileId: String) {
        showError(R.string.page_error_title, getString(R.string.cache_clear_failed))
        binding.retryButton.setOnClickListener {
            if (screen != Screen.ERROR || currentId != profileId) return@setOnClickListener
            val profile = config.profiles.firstOrNull { it.id == profileId }
            if (profile == null) launchLast() else openProfile(profile, emptySet(), clearWebCache = true)
        }
    }

    private fun showConfigImport(file: Uri? = null) {
        commitProfileOrder()
        val dialog = ConfigImportDialog(this, defaultUserAgent, { config.profiles.size }, {
            importFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/*", "application/octet-stream"))
        }, prepareBackup = ::prepareBackupData, backupRestored = { restored ->
            applyRestoredBackup(restored)
            toast(getString(R.string.import_success, restored.profiles.size))
        }, recoveryRequired = ::blockForBackupRecovery) { imported ->
            if (!persist(imported)) false else {
                profileOrderDirty = false
                rememberProfileFilter(profileFilter)
                cancelDownload(); destroyWebView(); currentId = null
                toast(getString(R.string.import_success, imported.profiles.size))
                launchLast()
                true
            }
        }
        showNativeDialog(dialog)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_config_dialog)
        file?.let { dialog.readFile(it) }
    }

    private fun showWebDav() {
        commitProfileOrder()
        hideKeyboard()
        showNativeDialog(WebDavDialog(this, defaultUserAgent, prepareData = ::prepareBackupData,
            restored = ::applyRestoredBackup, recoveryRequired = ::blockForBackupRecovery))
    }

    private fun prepareBackupData() {
        cancelDownload(); scriptLoader.close(); destroyWebView(); currentId = null
        showProfiles()
    }

    private fun applyRestoredBackup(restoredConfig: AppConfig) {
        config = restoredConfig
        configRecoveryRequired = false
        profileOrderDirty = false
        rememberProfileFilter(ProfileFilter(profileUiPreferences.getString("categoryId", null),
            profileUiPreferences.getBoolean("favoritesOnly", false)))
        showProfiles()
    }

    private fun showConfigRecovery() {
        showError(R.string.config_recovery_title, getString(R.string.config_recovery_message))
        binding.settingsButton.visibility = View.GONE
        binding.retryButton.setText(R.string.import_config)
        binding.retryButton.setOnClickListener { showConfigImport() }
        binding.editButton.setText(R.string.config_start_over)
        binding.editButton.setOnClickListener {
            showNativeDialog(AlertDialog.Builder(this).setTitle(R.string.config_start_over)
                .setMessage(R.string.config_start_over_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.config_start_over) { _, _ -> if (persist(AppConfig.EMPTY)) showProfiles() }
                .create())
        }
    }

    private fun blockForBackupRecovery() {
        backupRecoveryBlocked = true
        binding.settingsButton.isEnabled = false
        showError(R.string.page_error_title, getString(R.string.webdav_recovery_failed))
        binding.retryButton.setOnClickListener { recreate() }
        binding.editButton.visibility = View.GONE
    }

    private fun confirmExit() {
        showNativeDialog(ExitConfirmationDialog(this, config.lastProfile, screen == Screen.BROWSER) { finish() })
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
            runCatching { binding.webContainer.removeView(view) }
            runCatching { view.stopLoading() }
            runCatching { view.webChromeClient = null }
            // Keep onRenderProcessGone installed during teardown, and finish
            // destruction before a replacement WebView can start loading.
            runCatching { view.destroy() }.onFailure {
                DebugLog.event("webview", "destroy failed ${it.message.orEmpty().take(200)}")
            }
        }
        val retiringNetwork = mediaNetwork
        val retiringProxy = mediaProxy
        mediaNetwork = null
        mediaProxy = null
        runCatching { retiringNetwork?.close() }.onFailure {
            DebugLog.event("media-error", "network close failed ${it.message.orEmpty()}")
        }
        runCatching { retiringProxy?.close() }.onFailure {
            DebugLog.event("media-error", "proxy close failed ${it.message.orEmpty()}")
        }
        pageReady = false
        pageFailed = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pendingBackupExport", exportOptions != null)
        exportOptions?.let { outState.putBoolean("exportScriptData", it.scriptData); outState.putBoolean("exportPreferences", it.preferences) }
        outState.putBoolean("manager", screen == Screen.PROFILES)
        outState.putBoolean("editor", screen == Screen.EDITOR)
        outState.putString("editingId", editingId)
        outState.putString("profileCategoryFilter", profileFilter.categoryId)
        outState.putBoolean("profileFavoriteFilter", profileFilter.favoritesOnly)
        outState.putString("draftName", binding.nameInput.text.toString())
        outState.putString("draftCategory", binding.categoryInput.text.toString())
        outState.putBoolean("draftFavorite", binding.favoriteInput.isChecked)
        outState.putString("draftUrl", binding.websiteInput.text.toString())
        outState.putString("draftScript", binding.scriptInput.text.toString())
        outState.putString("draftNote", binding.noteInput.text.toString())
        outState.putString("draftScripts", ConfigCodec.referencesJson(draftScripts).toString())
        if (screen == Screen.EDITOR) {
            runCatching { editorDraftStore.save(editorDraftId, draftScriptLibrary, draftScripts) }
                .onSuccess { id -> editorDraftId = id; outState.putString("editorDraftId", id) }
                .onFailure { DebugLog.event("config", "editor draft save failed (${it.javaClass.simpleName})") }
        }
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
        webView?.onResume()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (nativeDialog as? ResponsiveDialog)?.updateLayout()
        updateChrome()
    }
    override fun onResume() { super.onResume(); updateChrome() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::binding.isInitialized && screen == Screen.PROFILES && event.actionMasked == MotionEvent.ACTION_DOWN) {
            binding.scriptLibrary.collapseIfOutside(event.rawX, event.rawY)
            binding.addButton.collapseIfOutside(event.rawX, event.rawY)
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onStop() {
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onStop()
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

package com.lightboat.app

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.lightboat.app.backup.BackupInfo
import com.lightboat.app.backup.BackupRepository
import com.lightboat.app.backup.BackupTransaction
import com.lightboat.app.backup.BackupOptions
import com.lightboat.app.backup.BackupOptionsStore
import com.lightboat.app.backup.OpenedBackup
import com.lightboat.app.backup.RemoteBackup
import com.lightboat.app.backup.WebDavClient
import com.lightboat.app.backup.WebDavConflict
import com.lightboat.app.backup.WebDavSettings
import com.lightboat.app.backup.WebDavSettingsStore
import com.lightboat.app.databinding.DialogWebdavBinding
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/** Manual backup/restore only; all network and large-file work stays off the UI thread. */
internal class WebDavDialog(activity: AppCompatActivity, defaultUserAgent: String,
    private val prepareData: () -> Unit, private val restored: (AppConfig) -> Unit,
    private val recoveryRequired: () -> Unit) :
    AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogWebdavBinding.inflate(activity.layoutInflater)
    private val settingsStore = WebDavSettingsStore(activity)
    private val optionsStore = BackupOptionsStore(activity)
    private val repository = BackupRepository(activity, defaultUserAgent)
    private val workspace = File(activity.cacheDir, "webdav-${UUID.randomUUID()}")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var network: WebDavClient? = null
    private var activeSettings: WebDavSettings? = null
    @Volatile private var closed = false
    private var busy = false
    private var critical = false
    private var pending: Pending? = null
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener { updateLayout() }
    private sealed interface Pending {
        data class Upload(val remote: RemoteBackup, val settings: WebDavSettings, val options: BackupOptions) : Pending
        data class Restore(val backup: OpenedBackup, val remote: RemoteBackup) : Pending
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setCanceledOnTouchOutside(false)
        ViewCompat.setAccessibilityHeading(binding.webdavTitle, true)
        val saved = settingsStore.load()
        binding.webdavDirectory.setText(saved.directory)
        binding.webdavUsername.setText(saved.username)
        binding.webdavPassword.setText(saved.password)
        binding.webdavBackupOptions.configure(optionsStore.load())
        binding.closeWebdav.setOnClickListener { if (!critical) dismiss() }
        binding.saveWebdav.setOnClickListener { readSettings()?.let { feedback(context.getString(R.string.webdav_saved)) } }
        binding.testWebdav.setOnClickListener {
            val settings = readSettings() ?: return@setOnClickListener
            val client = client(settings)
            task(context.getString(R.string.webdav_testing), { client.testConnection() }) { remote ->
                feedback(context.getString(if (remote.exists) R.string.webdav_connected_backup else R.string.webdav_connected_empty))
            }
        }
        binding.uploadWebdav.setOnClickListener { inspectUpload() }
        binding.downloadWebdav.setOnClickListener { download() }
        binding.webdavPreviewBack.setOnClickListener { pending = null; updateControls(); feedback(context.getString(R.string.webdav_ready)) }
        binding.webdavConfirm.setOnClickListener {
            when (val action = pending) {
                is Pending.Upload -> upload(action.settings, action.remote, action.options)
                is Pending.Restore -> applyRestore(action)
                null -> Unit
            }
        }
        showHistory()
        updateControls()
    }

    private fun readSettings(): WebDavSettings? {
        binding.webdavDirectory.error = null
        return try {
            val settings = WebDavSettings.normalized(binding.webdavDirectory.text.toString(),
                binding.webdavUsername.text.toString().trim(), binding.webdavPassword.text.toString())
            settingsStore.save(settings)
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.root.windowToken, 0)
            binding.root.clearFocus()
            settings
        } catch (error: Exception) {
            binding.webdavDirectory.error = error.message ?: context.getString(R.string.webdav_invalid_settings)
            binding.webdavDirectory.requestFocus(); null
        }
    }

    private fun client(settings: WebDavSettings): WebDavClient {
        if (activeSettings != settings || network == null) {
            network?.close(); network = WebDavClient(settings); activeSettings = settings
        }
        return requireNotNull(network)
    }

    private fun inspectUpload() {
        val settings = readSettings() ?: return
        val client = client(settings)
        val options = binding.webdavBackupOptions.options
        optionsStore.save(options)
        task(context.getString(R.string.webdav_checking_remote), { client.inspect() }) { remote ->
            if (!remote.exists) upload(settings, remote, options) else {
                pending = Pending.Upload(remote, settings, options)
                val changed = settingsStore.acceptedVersion() != remote.etag || remote.etag == null
                binding.webdavPreviewTitle.setText(if (changed) R.string.webdav_conflict_title else R.string.webdav_upload_title)
                binding.webdavPreviewDetails.text = context.getString(if (changed) R.string.webdav_conflict_message else R.string.webdav_replace_message,
                    remote.modified ?: context.getString(R.string.webdav_unknown_time))
                binding.webdavConfirm.setText(R.string.webdav_replace_remote)
                updateControls(); revealPreview()
            }
        }
    }

    private fun upload(settings: WebDavSettings, expected: RemoteBackup, options: BackupOptions) {
        prepareData()
        val client = client(settings)
        task(context.getString(R.string.webdav_uploading), {
            check(workspace.exists() || workspace.mkdirs())
            val file = File(workspace, "upload-${UUID.randomUUID()}.zip")
            try {
                val info = repository.create(file, workspace, options)
                check(!closed) { "操作已取消" }
                val result = client.upload(file, expected)
                settingsStore.accept(result.etag, false)
                info
            } finally { file.delete() }
        }) { info ->
            pending = null
            feedback(context.getString(R.string.webdav_upload_success, info.profiles, info.stores))
            showHistory(); updateControls()
            DebugLog.event("webdav", "backup uploaded profiles=${info.profiles} stores=${info.stores}")
        }
    }

    private fun download() {
        val settings = readSettings() ?: return
        val client = client(settings)
        task(context.getString(R.string.webdav_downloading), {
            check(workspace.exists() || workspace.mkdirs())
            val file = File(workspace, "download-${UUID.randomUUID()}.zip")
            try {
                val remote = client.download(file)
                repository.open(file, File(workspace, "received-${UUID.randomUUID()}")) to remote
            } finally { file.delete() }
        }) { (backup, remote) -> showRestore(backup, remote) }
    }

    private fun showRestore(backup: OpenedBackup, remote: RemoteBackup) {
        pending = Pending.Restore(backup, remote)
        binding.webdavRestoreOptions.configure(backup.info.options, backup.info.options, restore = true)
        binding.webdavPreviewTitle.setText(R.string.webdav_restore_title)
        binding.webdavPreviewDetails.text = describe(backup.info) + "\n\n" + context.getString(R.string.webdav_restore_message)
        binding.webdavConfirm.setText(R.string.webdav_restore_confirm)
        updateControls(); feedback(context.getString(R.string.webdav_validated)); revealPreview()
    }

    private fun applyRestore(action: Pending.Restore) {
        prepareData()
        val options = binding.webdavRestoreOptions.options
        task(context.getString(R.string.webdav_restoring), {
            val config = repository.restore(action.backup, options) { !closed }
            // A bookkeeping failure must not turn a completed restore into an apparent rollback.
            runCatching { settingsStore.accept(action.remote.etag, true) }
            config
        }, criticalOperation = true) { config ->
            pending = null
            restored(config)
            feedback(context.getString(R.string.webdav_restore_success))
            showHistory(); updateControls()
            DebugLog.event("webdav", "backup restored profiles=${config.profiles.size}")
        }
    }

    private fun describe(info: BackupInfo) = context.getString(R.string.webdav_backup_summary,
        formatTime(info.createdAt), info.device, info.profiles, info.scripts, info.stores,
        android.text.format.Formatter.formatShortFileSize(context, info.bytes))

    private fun showHistory() {
        binding.webdavHistory.text = context.getString(R.string.webdav_history,
            formatTime(settingsStore.uploadedAt()), formatTime(settingsStore.restoredAt()))
    }
    private fun formatTime(time: Long) = if (time == 0L) context.getString(R.string.webdav_never) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    private fun revealPreview() { binding.webdavScroll.post { binding.webdavPreview.requestRectangleOnScreen(Rect(0, 0, binding.webdavPreview.width, binding.webdavPreview.height), true) } }
    private fun feedback(message: String, error: Boolean = false) {
        binding.webdavStatus.text = message
        binding.webdavStatus.setTextColor(ContextCompat.getColor(context, if (error) R.color.error else R.color.muted))
    }
    private fun updateControls() {
        val preview = pending != null
        binding.webdavForm.visibility = if (preview) View.GONE else View.VISIBLE
        binding.webdavActions.visibility = if (preview) View.GONE else View.VISIBLE
        binding.webdavPreview.visibility = if (preview) View.VISIBLE else View.GONE
        binding.webdavRestoreOptions.visibility = if (pending is Pending.Restore) View.VISIBLE else View.GONE
        binding.webdavBackupOptions.setControlsEnabled(!busy)
        binding.webdavRestoreOptions.setControlsEnabled(!busy)
        listOf(binding.webdavDirectory, binding.webdavUsername, binding.webdavPassword, binding.testWebdav, binding.saveWebdav,
            binding.uploadWebdav, binding.downloadWebdav, binding.webdavPreviewBack, binding.webdavConfirm).forEach { it.isEnabled = !busy }
        binding.closeWebdav.isEnabled = !critical
        binding.webdavProgress.visibility = if (busy) View.VISIBLE else View.GONE
        setCancelable(!critical)
        updateLayout()
    }

    private fun <T> task(label: String, work: () -> T, criticalOperation: Boolean = false, complete: (T) -> Unit) {
        if (busy || closed) return
        busy = true; critical = criticalOperation; feedback(label); updateControls()
        worker.execute {
            val result = runCatching(work)
            main.post {
                if (closed) return@post
                busy = false; critical = false
                result.fold(onSuccess = complete, onFailure = { error ->
                    if (criticalOperation) pending = null
                    val message = when (error) {
                        is WebDavConflict -> { pending = null; error.message.orEmpty() }
                        is java.util.zip.ZipException -> context.getString(R.string.webdav_invalid_backup)
                        is java.io.InterruptedIOException -> context.getString(R.string.webdav_timeout)
                        is java.io.IOException -> context.getString(R.string.webdav_network_error)
                        is org.json.JSONException -> context.getString(R.string.webdav_invalid_backup)
                        else -> error.message ?: context.getString(R.string.webdav_failed)
                    }
                    feedback(message, true)
                    DebugLog.event("webdav", "operation failed type=${error.javaClass.simpleName}")
                    if (criticalOperation && BackupTransaction.requiresRecovery(context.filesDir)) {
                        dismiss(); recoveryRequired(); return@post
                    }
                })
                updateControls()
            }
        }
    }

    override fun onStart() { super.onStart(); binding.root.viewTreeObserver.addOnGlobalLayoutListener(layoutChanged); updateLayout() }
    override fun onStop() {
        if (closed) { super.onStop(); return }
        closed = true; network?.close(); main.removeCallbacksAndMessages(null)
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(layoutChanged)
        // A restore already exchanging files must reach commit or rollback.
        worker.execute { workspace.deleteRecursively() }; worker.shutdown()
        super.onStop()
    }
    override fun updateLayout() {
        val density = context.resources.displayMetrics.density
        val visible = Rect().also { window?.decorView?.getWindowVisibleDisplayFrame(it) }
        val width = minOf((600 * density).toInt(), context.resources.displayMetrics.widthPixels - (24 * density).toInt())
        val maximum = minOf((720 * density).toInt(), visible.height() - (24 * density).toInt()).coerceAtLeast(1)
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST))
        val height = binding.root.measuredHeight
        window?.let { if (it.attributes.width != width || it.attributes.height != height) it.setLayout(width, height) }
    }
}

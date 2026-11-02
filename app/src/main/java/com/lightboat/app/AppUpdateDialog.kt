package com.lightboat.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import com.lightboat.app.databinding.DialogAppUpdateBinding
import com.lightboat.app.update.AppRelease
import com.lightboat.app.update.GitHubUpdateClient
import com.lightboat.app.update.ReleaseVersion
import com.lightboat.app.update.UpdateApkVerifier
import com.lightboat.app.update.UpdateException
import com.lightboat.app.update.UpdateFailure
import java.io.File
import java.util.UUID

internal class AppUpdateDialog(
    private val activity: AppCompatActivity,
    private val client: GitHubUpdateClient = GitHubUpdateClient(),
    private val canInstall: () -> Boolean = { activity.packageManager.canRequestPackageInstalls() },
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogAppUpdateBinding.inflate(activity.layoutInflater)
    private val tasks = DialogWorker()
    private val main = Handler(Looper.getMainLooper())
    private val workspace = File(activity.cacheDir, "updates/${UUID.randomUUID()}")
    private val apkFile = File(workspace, "update.apk")
    private var release: AppRelease? = null
    private var ready = false
    private var installing = false
    private var lastPercent = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.updateTitle, true)
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        binding.updateCurrent.text = context.getString(R.string.update_current, installed.versionName, UpdateApkVerifier.code(installed))
        binding.updateClose.setOnClickListener { dismiss() }
        binding.updateAction.setOnClickListener {
            if (ready) install() else if (release != null) download() else check()
        }
        check()
    }

    private fun check() {
        busy(context.getString(R.string.update_checking))
        tasks.execute({ client.latest() }) { result ->
            binding.updateProgress.visibility = View.GONE
            result.onSuccess { latest ->
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val current = ReleaseVersion.parse(installed.versionName.orEmpty())
                release = latest
                binding.updateNotes.text = latest.notes.ifBlank { context.getString(R.string.update_no_notes) }
                binding.updateDetails.visibility = View.VISIBLE
                when {
                    current == null -> { release = null; failed(UpdateException(UpdateFailure.INVALID_RELEASE)) }
                    latest.version <= current -> {
                        binding.updateStatus.setText(R.string.update_latest)
                        binding.updateAction.visibility = View.GONE
                    }
                    latest.apk == null -> { release = null; failed(UpdateException(UpdateFailure.NO_APK)) }
                    else -> {
                        binding.updateStatus.text = context.getString(R.string.update_available, latest.version.name,
                            Formatter.formatShortFileSize(context, latest.apk.size))
                        action(R.string.update_download)
                    }
                }
                updateLayout()
            }.onFailure(::failed)
        }
    }

    private fun download() {
        val latest = release ?: return
        val apk = latest.apk ?: return
        busy(context.getString(R.string.update_downloading, 0))
        lastPercent = -1
        binding.updateProgress.isIndeterminate = false
        binding.updateProgress.progress = 0
        tasks.execute({
            check(workspace.mkdirs() || workspace.isDirectory)
            // Only previously handed-off installers survive closing a dialog; expire their cache.
            workspace.parentFile?.listFiles()?.filter { it != workspace && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                ?.forEach { it.deleteRecursively() }
            client.download(apk, apkFile) { done, total ->
                val percent = (done * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    main.post {
                        if (!tasks.closed) {
                            binding.updateProgress.progress = percent
                            binding.updateStatus.text = context.getString(R.string.update_downloading, percent)
                        }
                    }
                }
            }
            UpdateApkVerifier.verify(activity, apkFile, latest)
        }) { result ->
            binding.updateProgress.visibility = View.GONE
            result.onSuccess {
                ready = true
                binding.updateStatus.setText(R.string.update_ready)
                action(R.string.update_install)
            }.onFailure { error ->
                tasks.background { apkFile.delete() }
                failed(error)
            }
        }
    }

    private fun install() {
        try {
            if (!canInstall()) {
                binding.updateStatus.setText(R.string.update_allow_install)
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(intent)
            installing = true // Keep the file available while the system installer reads it.
            dismiss()
        } catch (_: ActivityNotFoundException) {
            binding.updateStatus.setText(R.string.update_installer_missing)
        } catch (_: SecurityException) {
            binding.updateStatus.setText(R.string.update_install_denied)
        }
    }

    private fun busy(message: String) {
        binding.updateStatus.setTextColor(context.getColor(R.color.ink))
        binding.updateStatus.text = message
        binding.updateProgress.isIndeterminate = true
        binding.updateProgress.visibility = View.VISIBLE
        binding.updateAction.visibility = View.VISIBLE
        binding.updateAction.isEnabled = false
    }

    private fun action(label: Int) {
        binding.updateAction.setText(label)
        binding.updateAction.isEnabled = true
        binding.updateAction.visibility = View.VISIBLE
    }

    private fun failed(error: Throwable) {
        binding.updateStatus.setTextColor(context.getColor(R.color.error))
        binding.updateStatus.setText(when ((error as? UpdateException)?.reason) {
            UpdateFailure.NO_RELEASE -> R.string.update_no_release
            UpdateFailure.RATE_LIMIT -> R.string.update_rate_limit
            UpdateFailure.INVALID_RELEASE -> R.string.update_bad_release
            UpdateFailure.NO_APK -> R.string.update_no_apk
            UpdateFailure.AMBIGUOUS_APK -> R.string.update_ambiguous_apk
            UpdateFailure.INVALID_APK -> R.string.update_bad_apk
            UpdateFailure.DOWNLOAD -> R.string.update_download_failed
            else -> R.string.update_network_failed
        })
        action(if (release == null) R.string.update_retry else R.string.update_download)
        updateLayout()
    }

    override fun onStart() { super.onStart(); updateLayout() }

    override fun updateLayout() {
        DialogLayout.apply(window, binding.root, DialogLayout.width(context),
            (DialogLayout.visibleHeight(window).takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels) - DialogLayout.dp(context, 24))
    }

    override fun onStop() {
        main.removeCallbacksAndMessages(null)
        client.close()
        tasks.close { if (!installing) workspace.deleteRecursively() }
        super.onStop()
    }
}

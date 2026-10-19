package com.lightboat.app

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import com.lightboat.app.backup.BackupOptions
import com.lightboat.app.backup.BackupOptionsStore
import com.lightboat.app.backup.BackupRepository
import com.lightboat.app.databinding.DialogBackupExportBinding
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal class BackupExportDialog(private val activity: AppCompatActivity, private val defaultUserAgent: String,
    private val selection: BackupOptions, private val destination: Uri? = null,
    private val prepareData: () -> Unit, private val chooseFile: (BackupOptions) -> Unit) :
    AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogBackupExportBinding.inflate(activity.layoutInflater)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val workspace = File(activity.cacheDir, "local-backup-${UUID.randomUUID()}")
    @Volatile private var closed = false
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        binding.backupExportOptions.configure(selection)
        binding.backupExportCancel.setOnClickListener { if (!busy) dismiss() }
        binding.backupExportAction.setOnClickListener {
            val options = binding.backupExportOptions.options
            BackupOptionsStore(context).save(options)
            dismiss(); chooseFile(options)
        }
        if (destination != null) export(destination)
    }
    private fun export(uri: Uri) {
        prepareData()
        busy = true; setCancelable(false)
        binding.backupExportCancel.isEnabled = false
        binding.backupExportAction.visibility = View.GONE
        binding.backupExportOptions.setControlsEnabled(false)
        binding.backupExportProgress.visibility = View.VISIBLE
        binding.backupExportFeedback.setText(R.string.backup_export_working)
        worker.execute {
            val result = runCatching {
                check(workspace.mkdirs())
                val archive = File(workspace, "backup.zip")
                val info = BackupRepository(context, defaultUserAgent).create(archive, workspace, selection)
                check(!closed) { "操作已取消" }
                (activity.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文件")).use { output ->
                    archive.inputStream().use { it.copyTo(output, 32 * 1024) }
                }
                info
            }
            main.post {
                if (closed) return@post
                busy = false; setCancelable(true)
                binding.backupExportCancel.isEnabled = true
                binding.backupExportCancel.setText(R.string.backup_done)
                binding.backupExportProgress.visibility = View.GONE
                binding.backupExportFeedback.text = result.fold({ context.getString(R.string.backup_export_complete, it.profiles, it.scripts) },
                    { it.message ?: context.getString(R.string.operation_error, "") })
            }
        }
    }
    override fun onStart() { super.onStart(); updateLayout() }
    override fun onStop() {
        if (!closed) {
            closed = true; main.removeCallbacksAndMessages(null)
            worker.execute { workspace.deleteRecursively() }; worker.shutdown()
        }
        super.onStop()
    }
    override fun updateLayout() {
        val density = context.resources.displayMetrics.density
        val visible = Rect().also { window?.decorView?.getWindowVisibleDisplayFrame(it) }
        val width = minOf((600 * density).toInt(), context.resources.displayMetrics.widthPixels - (24 * density).toInt())
        val maximum = (visible.height() - (24 * density).toInt()).coerceAtLeast(1)
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST))
        window?.setLayout(width, binding.root.measuredHeight)
    }
}

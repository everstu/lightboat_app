package com.lightboat.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.lightboat.app.databinding.DialogImportConfigBinding
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ConfigImportDialog(
    private val activity: AppCompatActivity,
    private val defaultUserAgent: String,
    private val currentCount: () -> Int,
    private val chooseFile: () -> Unit,
    private val applyImport: (AppConfig) -> Boolean,
) : AlertDialog(activity) {
    private val binding = DialogImportConfigBinding.inflate(activity.layoutInflater)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private var call: Call? = null
    private var imported: AppConfig? = null
    private var fileMode = false
    private var busy = false
    private var closed = false
    private var requestId = 0

    init {
        setView(binding.root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewCompat.setAccessibilityHeading(binding.importTitle, true)
        binding.urlSource.setOnClickListener { selectSource(false) }
        binding.fileSource.setOnClickListener { selectSource(true) }
        binding.filePanel.setOnClickListener { chooseLocalFile() }
        binding.importAction.setOnClickListener {
            val ready = imported
            if (ready != null) {
                if (applyImport(ready)) dismiss()
            } else if (fileMode) {
                chooseLocalFile()
            } else readUrl()
        }
        binding.importCancel.setOnClickListener {
            if (imported != null) {
                imported = null
                selectSource(fileMode)
            } else dismiss()
        }
        selectSource(false)
    }

    private fun chooseLocalFile() {
        if (!fileMode || busy || closed || imported != null) return
        hideKeyboard()
        chooseFile()
    }

    fun readFile(uri: Uri) {
        if (closed) return
        selectSource(true)
        load(R.string.import_loading_file) {
            (activity.contentResolver.openInputStream(uri) ?: error(context.getString(R.string.import_read_failed)))
                .use { ConfigImportReader.fromStream(it, defaultUserAgent) }
        }
    }

    private fun readUrl() {
        val url = try { AppConfig.normalizeUrl(binding.importUrl.text.toString()) }
        catch (error: IllegalArgumentException) {
            binding.importUrl.error = error.message
            binding.importUrl.requestFocus()
            return
        }
        binding.importUrl.error = null
        hideKeyboard()
        val download = try {
            client.newCall(Request.Builder().url(url)
                .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.5")
                .header("User-Agent", defaultUserAgent)
                .header("Cache-Control", "no-cache").build())
        } catch (_: IllegalArgumentException) {
            binding.importUrl.error = context.getString(R.string.url_invalid)
            return
        }
        call = download
        load(R.string.import_loading_url) { ConfigImportReader.fromUrl(download, defaultUserAgent) }
    }

    private fun load(label: Int, read: () -> AppConfig) {
        if (busy || closed) return
        val id = ++requestId
        busy = true
        imported = null
        updateControls()
        showFeedback(context.getString(label), false)
        worker.execute {
            val result = runCatching { read() }
            main.post {
                if (closed || id != requestId) return@post
                busy = false
                call = null
                result.onSuccess { config ->
                    imported = config
                    val summary = if (config.profiles.isEmpty()) {
                        context.getString(R.string.import_empty_confirm, currentCount())
                    } else context.getString(R.string.import_message, config.profiles.size, currentCount(),
                        config.lastProfile?.name ?: context.getString(R.string.import_no_startup))
                    showFeedback(summary, false)
                }.onFailure { error ->
                    val message = when (error) {
                        is IllegalArgumentException -> error.message ?: context.getString(R.string.import_read_failed)
                        is SocketTimeoutException, is java.io.InterruptedIOException -> context.getString(R.string.import_timeout)
                        is java.io.IOException -> context.getString(if (fileMode) R.string.import_read_failed else R.string.import_network_failed)
                        else -> context.getString(R.string.import_read_failed)
                    }
                    showFeedback(message, true)
                }
                updateControls()
            }
        }
    }

    private fun selectSource(useFile: Boolean) {
        if (busy) return
        fileMode = useFile
        imported = null
        binding.importFeedback.visibility = View.GONE
        updateControls()
    }

    private fun updateControls() {
        val preview = imported != null
        binding.urlSource.isSelected = !fileMode
        binding.fileSource.isSelected = fileMode
        binding.urlSource.isEnabled = !busy && !preview
        binding.fileSource.isEnabled = !busy && !preview
        binding.importUrl.isEnabled = !busy
        binding.filePanel.isEnabled = !busy && !preview
        binding.filePanel.alpha = if (binding.filePanel.isEnabled) 1f else 0.5f
        binding.urlPanel.visibility = if (!fileMode && !preview) View.VISIBLE else View.GONE
        binding.filePanel.visibility = if (fileMode && !preview) View.VISIBLE else View.GONE
        binding.importProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.importAction.apply {
            isEnabled = !busy
            setText(when {
                preview && imported!!.profiles.isEmpty() -> R.string.import_empty_action
                preview -> R.string.import_confirm
                fileMode -> R.string.import_choose_file
                else -> R.string.import_check
            })
        }
        binding.importCancel.setText(if (preview) R.string.back_import else R.string.import_cancel)
        val title = context.getString(if (preview) R.string.import_title else R.string.import_config)
        binding.importTitle.text = title
        ViewCompat.setAccessibilityPaneTitle(binding.root, title)
    }

    private fun showFeedback(message: String, error: Boolean) {
        binding.importFeedback.text = message
        binding.importFeedback.setTextColor(ContextCompat.getColor(context, if (error) R.color.error else R.color.ink))
        binding.importFeedback.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.root.clearFocus()
    }

    override fun onStop() {
        closed = true
        requestId++
        call?.cancel()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        client.connectionPool.evictAll()
        super.onStop()
    }
}

package com.lightboat.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightboat.app.databinding.DialogDebugLogsBinding
import com.lightboat.app.databinding.ItemDebugLogBinding
import java.util.concurrent.Executors

internal class DebugLogDialog(private val activity: AppCompatActivity) :
    AppCompatDialog(activity, R.style.Theme_Lightboat_DebugLogs) {
    private lateinit var binding: DialogDebugLogsBinding
    private val entries = LogAdapter()
    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private var cursor: DebugLog.Cursor? = null
    private var version = 0
    private var readRequestId = 0
    private var active = false
    private var closed = false
    private var reading = false
    private var clearing = false
    private var paused = false
    private var followTail = true
    private var readFailed = false
    private val refresh = Runnable { refreshLogs() }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> { active = true; refreshLogs() }
            Lifecycle.Event.ON_STOP -> { active = false; version++; main.removeCallbacks(refresh) }
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogDebugLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.logList.layoutManager = LinearLayoutManager(context)
        binding.logList.adapter = entries
        binding.logList.itemAnimator = null
        binding.logList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) followTail = false
                if (newState == RecyclerView.SCROLL_STATE_IDLE) followTail = !recyclerView.canScrollVertically(1)
                updateStatus()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    followTail = !recyclerView.canScrollVertically(1)
                    updateStatus()
                }
            }
        })
        binding.closeLogs.setOnClickListener { dismiss() }
        binding.pauseLogs.setOnClickListener {
            paused = !paused
            version++ // Discard a read that was already in flight when pause was tapped.
            main.removeCallbacks(refresh)
            updateStatus()
            if (!paused) refreshLogs()
        }
        binding.latestLogs.setOnClickListener {
            binding.logList.stopScroll()
            followTail = true
            scrollToLatest()
            updateStatus()
        }
        binding.clearLogs.setOnClickListener { clearLogs() }
        binding.copyLogs.setOnClickListener {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.debug_logs), entries.text()))
            if (Build.VERSION.SDK_INT < 33) toast(R.string.logs_copied)
        }
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        activity.lifecycle.addObserver(lifecycleObserver)
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStop() {
        closed = true
        active = false
        version++
        activity.lifecycle.removeObserver(lifecycleObserver)
        main.removeCallbacksAndMessages(null)
        reader.shutdown()
        super.onStop()
    }

    private fun refreshLogs() {
        if (!active || closed || paused || clearing || reading) return
        main.removeCallbacks(refresh)
        val requestedVersion = version
        val requestedReadId = ++readRequestId
        val from = cursor
        reading = true
        reader.execute {
            val result = runCatching { DebugLog.readAfter(from) }
            val rows = result.getOrNull()?.text?.lineSequence()
                ?.filter(String::isNotBlank)?.map(::LogEntry)?.toList().orEmpty()
            main.post {
                val isCurrentRead = requestedReadId == readRequestId
                if (isCurrentRead) reading = false
                if (closed) return@post
                if (isCurrentRead && active && !paused && requestedVersion == version) {
                    result.onSuccess { update ->
                        readFailed = false
                        applyUpdate(update, rows)
                    }.onFailure { readFailed = true }
                    updateStatus()
                }
                if (isCurrentRead && active && !paused && !clearing) main.postDelayed(refresh, 500L)
            }
        }
    }

    private fun applyUpdate(update: DebugLog.Update, rows: List<LogEntry>) {
        cursor = update.cursor
        if (!update.reset && rows.isEmpty()) return
        val layout = binding.logList.layoutManager as LinearLayoutManager
        val anchor = layout.findFirstVisibleItemPosition()
        val anchorText = entries.at(anchor)?.raw
        val anchorTop = layout.findViewByPosition(anchor)?.let { layout.getDecoratedTop(it) }
        if (update.reset) entries.replace(rows) else entries.append(rows)
        binding.emptyTitle.setText(R.string.logs_empty)
        binding.emptyLogs.visibility = if (entries.itemCount == 0) View.VISIBLE else View.GONE
        if (followTail) scrollToLatest() else if (update.reset && anchorText != null && anchorTop != null) {
            // File trimming can remove old rows. Keep a surviving row at its old offset,
            // or stay at the oldest remaining row instead of jumping to the tail.
            val restored = rows.indexOfFirst { it.raw == anchorText }.coerceAtLeast(0)
            layout.scrollToPositionWithOffset(restored, anchorTop - binding.logList.paddingTop)
        }
    }

    private fun scrollToLatest() {
        val requestedVersion = version
        binding.logList.post {
            if (!closed && requestedVersion == version && followTail &&
                binding.logList.scrollState == RecyclerView.SCROLL_STATE_IDLE && entries.itemCount > 0) {
                binding.logList.doOnNextLayout {
                    if (!closed && requestedVersion == version && followTail &&
                        binding.logList.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layout = binding.logList.layoutManager as LinearLayoutManager
                        layout.findViewByPosition(entries.itemCount - 1)?.let { last ->
                            // A wrapped log row can be taller than the viewport. Reveal its
                            // actual bottom too, rather than stopping at the start of that row.
                            val remaining = layout.getDecoratedBottom(last) - binding.logList.height + binding.logList.paddingBottom
                            if (remaining > 0) binding.logList.scrollBy(0, remaining)
                        }
                    }
                }
                binding.logList.scrollToPosition(entries.itemCount - 1)
            }
        }
    }

    private fun clearLogs() {
        if (clearing || closed) return
        clearing = true
        version++
        readRequestId++ // A read queued ahead of this clear may not overwrite the empty view.
        main.removeCallbacks(refresh)
        updateStatus()
        reader.execute {
            val result = runCatching { DebugLog.clear() }
            main.post {
                clearing = false
                reading = false
                if (closed) return@post
                result.onSuccess {
                    cursor = null
                    entries.replace(emptyList())
                    followTail = true
                    readFailed = false
                    binding.emptyTitle.setText(R.string.logs_empty)
                    binding.emptyLogs.visibility = View.VISIBLE
                }.onFailure { toast(R.string.logs_clear_failed) }
                updateStatus()
                refreshLogs()
            }
        }
    }

    private fun updateStatus() {
        binding.logStatus.setText(if (paused) R.string.logs_paused else R.string.logs_live)
        binding.logStatus.setTextColor(ContextCompat.getColor(context, if (paused) R.color.log_warning else R.color.accent))
        binding.logStatus.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (paused) R.color.log_paused_badge else R.color.log_badge))
        binding.pauseLogs.setText(if (paused) R.string.logs_resume else R.string.logs_pause)
        binding.pauseLogs.isEnabled = !clearing
        binding.clearLogs.isEnabled = !clearing
        binding.copyLogs.isEnabled = !clearing && entries.itemCount > 0
        binding.logCount.text = context.getString(R.string.logs_count, entries.itemCount)
        binding.logHint.setText(when {
            readFailed -> R.string.logs_read_failed
            paused -> R.string.logs_paused_hint
            followTail -> R.string.logs_following
            else -> R.string.logs_reading_history
        })
        binding.latestLogs.visibility = if (!followTail && entries.itemCount > 0) View.VISIBLE else View.GONE
    }

    private fun toast(message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private class LogEntry(val raw: String) {
        private val match = LINE.matchEntire(raw)
        val time = match?.groupValues?.get(1).orEmpty()
        val source = match?.groupValues?.get(2) ?: "app"
        val message = match?.groupValues?.get(3) ?: raw
        val color = when {
            source.contains("error") || source.contains("crash") || message.contains(" failed ") ||
                message.startsWith("error ", ignoreCase = true) -> R.color.error
            message.contains("timeout", ignoreCase = true) || message.startsWith("warn ", ignoreCase = true) -> R.color.log_warning
            else -> R.color.accent
        }

        companion object {
            private val LINE = Regex("^\\d{4}-\\d{2}-\\d{2} (\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[([^]]+)] (.*)$")
        }
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
        class Holder(val binding: ItemDebugLogBinding) : RecyclerView.ViewHolder(binding.root)
        private val rows = mutableListOf<LogEntry>()
        fun at(position: Int) = rows.getOrNull(position)
        fun text() = rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n") { it.raw }

        @SuppressLint("NotifyDataSetChanged")
        fun replace(next: List<LogEntry>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        fun append(next: List<LogEntry>) {
            val start = rows.size
            rows.addAll(next)
            if (next.isNotEmpty()) notifyItemRangeInserted(start, next.size)
        }

        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDebugLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = rows[position]
            val row = holder.binding
            val color = ContextCompat.getColor(row.root.context, entry.color)
            row.entryTime.text = entry.time
            row.entrySource.text = entry.source
            row.entrySource.setTextColor(color)
            row.entryMarker.setBackgroundColor(color)
            row.entryMessage.text = entry.message
            row.entryMessage.setTextColor(ContextCompat.getColor(row.root.context,
                if (entry.color == R.color.error) R.color.error else R.color.ink))
        }
    }
}

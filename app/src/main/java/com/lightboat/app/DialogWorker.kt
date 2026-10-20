package com.lightboat.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Background runner shared by the backup/import dialogs: work runs on one worker
 * thread, results are delivered on the main thread unless the dialog stopped, and
 * shutdown drops pending callbacks before running [close]'s cleanup on the worker.
 */
internal class DialogWorker {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var closed = false; private set

    fun <T> execute(work: () -> T, onResult: (Result<T>) -> Unit) {
        worker.execute {
            val result = runCatching(work)
            main.post { if (!closed) onResult(result) }
        }
    }

    /** Fire-and-forget work on the worker thread, e.g. deleting scratch files. */
    fun background(task: () -> Unit) {
        worker.execute { runCatching(task) }
    }

    fun close(cleanup: () -> Unit = {}) {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.execute { runCatching(cleanup) }
        worker.shutdown()
    }
}

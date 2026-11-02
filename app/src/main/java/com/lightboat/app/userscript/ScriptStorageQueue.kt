package com.lightboat.app.userscript

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded, ordered storage work. Saturation never runs disk I/O on the caller. */
internal class ScriptStorageQueue(capacity: Int = 64) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity),
        { task -> Thread(task, "userscript-storage").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    fun submit(task: () -> Unit): Boolean = try {
        worker.execute { task() }
        true
    } catch (_: RejectedExecutionException) { false }

    override fun close() { worker.shutdownNow() }
}

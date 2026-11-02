package com.lightboat.app

import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.Executors

/** TLS socket cancellation/close can write to the network, even for idle sockets. */
internal object HttpClientCleanup {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lightboat-http-close").apply { isDaemon = true }
    }
    fun close(client: OkHttpClient, calls: Collection<Call>) {
        worker.execute {
            try { calls.forEach { it.cancel() } }
            finally {
                try { client.connectionPool.evictAll() }
                finally { client.dispatcher.executorService.shutdown() }
            }
        }
    }
}

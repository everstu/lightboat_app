package com.lightboat.app

import com.lightboat.app.userscript.ScriptStorageQueue
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScriptStorageQueueTest {
    @Test fun saturationRejectsWithoutRunningOnTheCallerAndAcceptedTasksKeepTheirOrder() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val rejectedRan = AtomicBoolean(false)
        val order = mutableListOf<Int>()
        ScriptStorageQueue(1).use { queue ->
            try {
                assertTrue(queue.submit {
                    started.countDown()
                    release.await()
                    order.add(1)
                })
                assertTrue(started.await(3, TimeUnit.SECONDS))
                assertTrue(queue.submit { order.add(2); finished.countDown() })
                assertFalse(queue.submit { rejectedRan.set(true) })
                assertFalse(rejectedRan.get())
                release.countDown()
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                assertEquals(listOf(1, 2), order)
                val recovered = CountDownLatch(1)
                assertTrue(queue.submit { recovered.countDown() })
                assertTrue(recovered.await(3, TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }
}

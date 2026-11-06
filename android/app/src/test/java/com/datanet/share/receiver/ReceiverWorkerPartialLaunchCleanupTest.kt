package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverWorkerPartialLaunchCleanupTest {
    @Test fun nativeThreadFailureIsReportedWithoutSwallowingOtherFailures() {
        assertFalse(ReceiverWorkerLaunchGuard.start { throw OutOfMemoryError("native thread limit") })
        assertTrue(ReceiverWorkerLaunchGuard.start { })
        try {
            ReceiverWorkerLaunchGuard.start { throw IllegalStateException("unexpected") }
            fail("ordinary startup errors must reach the existing error handler")
        } catch (_: IllegalStateException) { }
    }

    @Test fun secondWorkerFailureLeavesFirstWorkerAvailableForOwnerCleanup() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val running = AtomicBoolean(true)
        val worker = Thread({
            started.countDown()
            try {
                while (running.get()) Thread.sleep(10)
            } catch (_: InterruptedException) { /* owner cleanup */ }
            finally { stopped.countDown() }
        }, "test-tun-read")
        worker.isDaemon = true
        try {
            assertFalse(ReceiverWorkerLaunchGuard.start {
                worker.start()
                assertTrue("first worker must have started", started.await(2, TimeUnit.SECONDS))
                throw OutOfMemoryError("second worker start failed")
            })
        } finally {
            // Mirrors ReceiverVpnService's already-owned bridge cleanup.
            running.set(false)
            worker.interrupt()
            assertTrue("owned worker must exit after cleanup", stopped.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
        }
        assertFalse(worker.isAlive)
    }
}

package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SenderHealthProbeInterruptTest {
    @Test fun interruptedSocketPreparationClosesProbeAndPreservesCancellation() {
        val preparing = CountDownLatch(1)
        val candidate = AtomicReference<Socket>()
        val result = AtomicReference<Boolean>()
        val interrupted = AtomicBoolean(false)
        val worker = Thread({
            result.set(SenderHealthProbe.check("127.0.0.1", 9,
                "synthetic-health-password", timeoutMs = 500) { socket ->
                candidate.set(socket)
                preparing.countDown()
                Thread.sleep(10_000)
                true
            })
            interrupted.set(Thread.currentThread().isInterrupted)
        }, "test-health-probe-interruption").apply { isDaemon = true; start() }
        try {
            assertTrue("probe did not enter preparation", preparing.await(3, TimeUnit.SECONDS))
        } finally {
            worker.interrupt()
            worker.join(3_000)
        }
        assertFalse("probe did not stop after interruption", worker.isAlive)
        assertEquals(false, result.get())
        assertTrue("cancelled probe socket must be closed", candidate.get().isClosed)
        assertTrue("watchdog must retain its interrupt signal", interrupted.get())
    }
}

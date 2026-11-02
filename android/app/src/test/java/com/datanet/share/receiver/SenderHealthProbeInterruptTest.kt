package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SenderHealthProbeInterruptTest {
    @Test fun cancelledAfterSuccessfulSocketSetupNeverConnectsToSender() {
        // Stop can arrive while Network.bindSocket()/VpnService.protect() runs,
        // even if the setup callback then returns true without throwing.
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 1_200
            val accepted = AtomicBoolean(false)
            val acceptor = Thread({
                try { listener.accept().use { accepted.set(true) } }
                catch (_: java.net.SocketTimeoutException) { }
            }, "test-cancelled-probe-listener").apply { isDaemon = true; start() }
            val candidate = AtomicReference<Socket>()
            val preserved = AtomicBoolean(false)
            val worker = Thread({
                SenderHealthProbe.check("127.0.0.1", listener.localPort,
                    "synthetic-health-password", timeoutMs = 300) { socket ->
                    candidate.set(socket)
                    Thread.currentThread().interrupt()
                    true
                }
                preserved.set(Thread.currentThread().isInterrupted)
            }, "test-cancel-during-setup").apply { isDaemon = true; start() }
            worker.join(2_000)
            acceptor.join(2_000)
            assertFalse("cancelled health probe worker stalled", worker.isAlive)
            assertFalse("cancelled probe must not connect after setup", accepted.get())
            assertTrue("cancelled probe must close its prepared socket", candidate.get().isClosed)
            assertTrue("cancelled probe must retain interruption", preserved.get())
        }
    }
    @Test fun alreadyInterruptedWatchdogNeverPreparesOrConnectsProbeSocket() {
        val setupCalls = java.util.concurrent.atomic.AtomicInteger()
        val result = AtomicReference<Boolean>()
        val interruptPreserved = AtomicBoolean(false)
        val worker = Thread({
            Thread.currentThread().interrupt()
            result.set(SenderHealthProbe.check("127.0.0.1", 9,
                "synthetic-health-password", timeoutMs = 500) {
                setupCalls.incrementAndGet()
                true
            })
            interruptPreserved.set(Thread.currentThread().isInterrupted)
        }, "test-preinterrupted-health-probe").apply { isDaemon = true; start() }
        worker.join(3_000)
        assertFalse("pre-interrupted probe did not finish", worker.isAlive)
        assertEquals(false, result.get())
        assertEquals("a cancelled watchdog must not prepare a network socket", 0, setupCalls.get())
        assertTrue("pre-existing cancellation must remain visible", interruptPreserved.get())
    }

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

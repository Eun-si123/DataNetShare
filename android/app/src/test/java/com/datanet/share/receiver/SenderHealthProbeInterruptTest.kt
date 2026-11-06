package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SenderHealthProbeInterruptTest {
    @Test fun socketRegistrationRejectsStoppedSessionAndDoesNotClearNewSocket() {
        val guard = SenderHealthProbeSocketGuard()
        val old = Socket()
        val current = Socket()
        val late = Socket()
        try {
            assertTrue(guard.register(old) { true })
            guard.closeActive()
            assertTrue(old.isClosed)
            assertFalse(guard.register(late) { false })
            assertTrue(guard.register(current) { true })
            guard.unregister(old)
            guard.closeActive()
            assertTrue("old callback must not clear new probe socket", current.isClosed)
            assertFalse("rejected socket is caller-owned", late.isClosed)
        } finally {
            old.close(); current.close(); late.close()
        }
    }

    @Test fun cancelledDuringDelayedSocksMethodResponseNeverSendsCredentials() {
        val guard = SenderHealthProbeSocketGuard()
        val stopped = AtomicBoolean(false)
        val greetingSeen = CountDownLatch(1)
        val allowMethodResponse = CountDownLatch(1)
        val acceptedBytes = AtomicReference<ByteArray>()
        val observedSocket = AtomicReference<Socket>()
        val probeResult = AtomicReference<Boolean>()
        val interrupted = AtomicBoolean(false)
        val senderError = AtomicReference<Throwable>()
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val server = Thread({
                try {
                    listener.accept().use { peer ->
                        peer.soTimeout = 1_000
                        val input = java.io.DataInputStream(peer.getInputStream())
                        val greeting = ByteArray(3)
                        input.readFully(greeting)
                        acceptedBytes.set(greeting)
                        greetingSeen.countDown()
                        // Stop wins before server sends the SOCKS authentication method.
                        if (!allowMethodResponse.await(3, TimeUnit.SECONDS)) return@use
                        try {
                            peer.getOutputStream().write(byteArrayOf(5, 2))
                            peer.getOutputStream().flush()
                        } catch (_: java.io.IOException) { /* closed watchdog socket */ }
                        try {
                            val read = input.read()
                            if (read != -1) senderError.set(AssertionError("cancelled probe sent credentials"))
                        } catch (_: java.io.IOException) { /* peer socket already closed */ }
                    }
                } catch (error: Throwable) {
                    senderError.set(error)
                    greetingSeen.countDown()
                }
            }, "test-delayed-health-method").apply { isDaemon = true; start() }
            val worker = Thread({
                probeResult.set(SenderHealthProbe.check(
                    "127.0.0.1", listener.localPort, "synthetic-secret", timeoutMs = 1_000,
                    onSocketClosed = { guard.unregister(it) },
                    setup = { socket ->
                        observedSocket.set(socket)
                        guard.register(socket) { !stopped.get() }
                    }
                ))
                interrupted.set(Thread.currentThread().isInterrupted)
            }, "test-cancelled-health-watchdog").apply { isDaemon = true; start() }
            try {
                assertTrue("SOCKS method greeting was never seen", greetingSeen.await(3, TimeUnit.SECONDS))
                assertArrayEquals(byteArrayOf(5, 1, 2), acceptedBytes.get())
                stopped.set(true)
                worker.interrupt()
                guard.closeActive()
                allowMethodResponse.countDown()
                worker.join(3_000)
                server.join(3_000)
                assertFalse("watchdog worker did not exit after socket close", worker.isAlive)
                assertFalse("sender fixture did not exit", server.isAlive)
                assertEquals(false, probeResult.get())
                assertTrue("closed watchdog socket remained active", observedSocket.get().isClosed)
                assertTrue("watchdog interrupt flag must remain set", interrupted.get())
                assertNull("cancelled probe sent additional data", senderError.get())
            } finally {
                stopped.set(true)
                worker.interrupt()
                guard.closeActive()
                allowMethodResponse.countDown()
                worker.join(3_000)
                server.join(3_000)
            }
        }
    }

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

package com.datanet.share.receiver

import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class SenderHealthProbeTest {
    @Test fun authenticatedHealthySenderIsRecognizedWithoutOpeningInternetDestination() {
        ProxyFixture().use { fixture ->
            val setupCalls = AtomicInteger()
            assertTrue(SenderHealthProbe.check("127.0.0.1", fixture.proxy.boundPort,
                fixture.password, timeoutMs = 1500) { socket ->
                assertFalse(socket.isConnected)
                setupCalls.incrementAndGet()
                true
            })
            assertEquals(1, setupCalls.get())
        }
    }

    @Test fun disconnectedSenderIsRejectedWithoutIndefiniteRetry() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(SenderHealthProbe.check("127.0.0.1", port, "test-session-password",
            timeoutMs = 500, setup = { true }))
    }

    @Test fun wrongPasswordAndFailedSocketProtectionAreNotTreatedAsHealthy() {
        ProxyFixture().use { fixture ->
            assertFalse(SenderHealthProbe.check("127.0.0.1", fixture.proxy.boundPort,
                "not-the-pairing-password", timeoutMs = 1500, setup = { true }))
            assertFalse(SenderHealthProbe.check("127.0.0.1", fixture.proxy.boundPort,
                fixture.password, timeoutMs = 1500, setup = { false }))
        }
    }

    @Test fun policyNeedsConsecutiveFailuresAndRecoversOnSuccess() {
        val policy = SenderHealthPolicy(missLimit = 3)
        assertFalse(policy.observe(false))
        assertFalse(policy.observe(false))
        assertFalse(policy.observe(true))
        assertFalse(policy.observe(false))
        assertFalse(policy.observe(false))
        assertTrue(policy.observe(false))
        assertTrue(policy.observe(false))
        policy.reset()
        assertFalse(policy.observe(false))
    }

    @Test fun policyRejectsAnUnsafeSingleFailureThreshold() {
        try {
            SenderHealthPolicy(1)
            fail("one missed heartbeat should never disconnect a VPN")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun rejectedSocketPreparationClosesProbeBeforeConnect() {
        lateinit var candidate: Socket
        assertFalse(SenderHealthProbe.check("127.0.0.1", 9,
            "synthetic-password", timeoutMs = 500) {
            candidate = it
            false
        })
        assertFalse(candidate.isConnected)
        assertTrue(candidate.isClosed)
    }

    @Test fun throwingSocketPreparationClosesProbeBeforeConnect() {
        lateinit var candidate: Socket
        assertFalse(SenderHealthProbe.check("127.0.0.1", 9,
            "synthetic-password", timeoutMs = 500) {
            candidate = it
            throw java.io.IOException("synthetic link unavailable")
        })
        assertFalse(candidate.isConnected)
        assertTrue(candidate.isClosed)
    }

    @Test fun policyClearsLatchedMissesWhenSenderRecovers() {
        val policy = SenderHealthPolicy(missLimit = 3)
        assertFalse(policy.observe(false))
        assertFalse(policy.observe(false))
        assertTrue(policy.observe(false))
        assertFalse(policy.observe(true))
        assertFalse(policy.observe(false))
        assertFalse(policy.observe(false))
        assertTrue(policy.observe(false))
    }

    @Test fun probeEndsAfterAuthenticationWithoutSendingAnySocksRequest() {
        // A health check must not send CONNECT/UDP ASSOCIATE: it is not a
        // destination reachability test and must not consume upstream data.
        val password = "synthetic-probe-password"
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
            val observed = java.util.concurrent.LinkedBlockingQueue<Any>()
            val worker = Thread({
                try {
                    listener.accept().use { peer ->
                        peer.soTimeout = 2_000
                        val input = java.io.DataInputStream(peer.getInputStream())
                        com.datanet.share.common.Socks5.authenticateServer(
                            input, peer.getOutputStream(), password)
                        // EOF is -1. A SOCKS request would start with version 5.
                        observed.offer(input.read())
                    }
                } catch (failure: Exception) {
                    observed.offer(failure)
                }
            }, "test-health-probe-auth-only").apply { isDaemon = true; start() }

            assertTrue(SenderHealthProbe.check("127.0.0.1", listener.localPort,
                password, timeoutMs = 1_500, setup = { true }))
            assertEquals("health probe must close immediately after auth, without a SOCKS request",
                -1, observed.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            worker.join(2_000)
            assertFalse("fake sender worker must finish", worker.isAlive)
        }
    }
}

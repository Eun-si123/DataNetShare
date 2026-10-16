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
}

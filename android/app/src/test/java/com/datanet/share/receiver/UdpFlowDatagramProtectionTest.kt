package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real loopback SOCKS association; the Android-only protection callback is injected. */
class UdpFlowDatagramProtectionTest {
    @Test fun rejectedDatagramProtectionNeverStartsRelayAndClosesResources() = ProxyFixture().use { fixture ->
        verifyProtectionFailure(fixture) { false }
    }

    @Test fun thrownDatagramProtectionDoesNotExposeExceptionDetails() = ProxyFixture().use { fixture ->
        verifyProtectionFailure(fixture) {
            throw IOException("synthetic private interface and password")
        }
    }

    private fun verifyProtectionFailure(
        fixture: ProxyFixture,
        protectDatagram: (DatagramSocket) -> Boolean
    ) {
        val datagram = AtomicReference<DatagramSocket?>()
        val errors = mutableListOf<String>()
        val closes = AtomicInteger()
        val connected = AtomicInteger()
        val transmitted = AtomicInteger()
        val flow = UdpFlow(
            FlowKey(0x0a000001, 19001, 0x7f000001, fixture.udpPort),
            "127.0.0.1", fixture.proxy.boundPort, fixture.password,
            { true },
            { socket -> datagram.set(socket); protectDatagram(socket) },
            { fail("a rejected UDP relay must not write to the VPN") },
            {}, { transmitted.addAndGet(it) }, { closes.incrementAndGet() },
            onError = { errors += it },
            onConnected = { connected.incrementAndGet() }
        )
        try {
            flow.send(byteArrayOf(1)) // Exercise cleanup of an already-queued first packet.
            flow.start() // Synchronous: returns when datagram protection fails.
            assertNotNull("UDP socket should be created after SOCKS association", datagram.get())
            assertTrue("failed protection must close the UDP socket", datagram.get()!!.isClosed)
            assertEquals("the flow must be removed exactly once", 1, closes.get())
            assertEquals("failed protection must never count as connected", 0, connected.get())
            assertEquals("queued traffic must not be sent unprotected", 0, transmitted.get())
            assertEquals(1, errors.size)
            assertTrue(errors.single().startsWith("UDP proxy setup/relay failed"))
            assertFalse(errors.single().contains("synthetic private"))
            assertFalse(errors.single().contains(fixture.password))
        } finally {
            flow.close()
        }
        assertEquals("repeated close must not invoke removal again", 1, closes.get())
    }
}

package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TcpFlowErrorCallbackCleanupTest {
    @Test fun throwingDiagnosticCallbackOnHandshakeTimeoutStillReleasesFlow() {
        val callbackFailure = IllegalStateException("synthetic diagnostics failure")
        val closes = AtomicInteger()
        val errors = AtomicInteger()
        val packets = mutableListOf<Ipv4Packet>()
        val flow = TcpFlow(FlowKey(0x0a000001, 50002, 0x08080808, 443),
            100, "127.0.0.1", 1, "unused",
            { throw IOException("unexpected connect") },
            { packets += Ipv4Packet.parse(it)!! },
            {}, {}, { closes.incrementAndGet() }, initialSequence = 1000,
            onError = { errors.incrementAndGet(); throw callbackFailure })
        try {
            flow.start()
            try {
                flow.tick(Long.MAX_VALUE)
                fail("diagnostic callback should propagate its failure")
            } catch (actual: IllegalStateException) {
                assertSame(callbackFailure, actual)
            }
            assertEquals(1, errors.get())
            assertEquals(1, closes.get())
            assertEquals(0x14, packets.last().flags)
            flow.close()
            assertEquals(1, closes.get())
        } finally { flow.close() }
    }

    @Test fun throwingDiagnosticCallbackStillAbortsAndReleasesFlow() {
        val callbackFailure = IllegalStateException("synthetic diagnostics failure")
        val closes = AtomicInteger()
        val errors = AtomicInteger()
        val packets = mutableListOf<Ipv4Packet>()
        val flow = TcpFlow(FlowKey(0x0a000001, 50001, 0x08080808, 443),
            100, "127.0.0.1", 1, "unused",
            { throw IOException("synthetic protect failure") },
            { packets += Ipv4Packet.parse(it)!! },
            {}, {}, { closes.incrementAndGet() }, initialSequence = 1000,
            onError = { errors.incrementAndGet(); throw callbackFailure })
        try {
            flow.start()
            // Establish the synthetic app-side handshake without a real upstream.
            val synAck = packets.single()
            flow.receive(Ipv4Packet(FlowKey(0x0a000001, 50001, 0x08080808, 443),
                6, 101, synAck.sequence + 1, 0x10, 65535, ByteArray(0)))
            try {
                flow.openUpstream()
                fail("diagnostic callback should propagate its failure")
            } catch (actual: IllegalStateException) {
                assertSame(callbackFailure, actual)
            }
            assertEquals(1, errors.get())
            assertEquals("flow slot released even if diagnostics throws", 1, closes.get())
            assertEquals(0x14, packets.last().flags)
            flow.close()
            assertEquals(1, closes.get())
        } finally { flow.close() }
    }
}

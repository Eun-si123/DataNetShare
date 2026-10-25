package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TcpFlowAbortCleanupTest {
    private val key = FlowKey(0x0a000001, 55001, 0x08080808, 443)

    @Test fun abortClosesAndPreservesWriteFailureWhenTunRejectsReset() {
        val failure = IOException("synthetic TUN write failure")
        val writes = AtomicInteger()
        val closes = AtomicInteger()
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "unused",
            { true }, { writes.incrementAndGet(); throw failure },
            {}, {}, { closes.incrementAndGet() }, initialSequence = 1000)

        try {
            flow.abort()
            fail("RST write failure should propagate")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals("flow must close even when the RST cannot be written", 1, closes.get())
        assertEquals(1, writes.get())
        flow.abort() // Already closed: no second RST or cleanup callback.
        assertEquals(1, closes.get())
        assertEquals(1, writes.get())
    }

    @Test fun successfulAbortStillSendsResetAndClosesOnce() {
        val packets = mutableListOf<Ipv4Packet>()
        val closes = AtomicInteger()
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "unused",
            { true }, { packets += Ipv4Packet.parse(it)!! },
            {}, {}, { closes.incrementAndGet() }, initialSequence = 1000)

        flow.abort()
        flow.abort()
        assertEquals(1, packets.size)
        assertEquals(0x14, packets.single().flags)
        assertEquals(1, closes.get())
    }
}

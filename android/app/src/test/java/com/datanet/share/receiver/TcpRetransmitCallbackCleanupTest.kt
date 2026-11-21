package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TcpRetransmitCallbackCleanupTest {
    @Test fun exhaustedRetransmissionsCloseFlowEvenIfErrorReporterThrows() {
        val failure = IllegalStateException("synthetic diagnostic callback failure")
        val packets = mutableListOf<Ipv4Packet>()
        val closes = AtomicInteger()
        val flow = TcpFlow(FlowKey(0x0a000001, 55001, 0x08080808, 443),
            100, "127.0.0.1", 1, "unused", { true },
            { packets += Ipv4Packet.parse(it)!! }, {}, {},
            { closes.incrementAndGet() }, initialSequence = 1000,
            onError = { throw failure })
        flow.start()
        // Force only the retry counter to its boundary; no real-time sleeps or network needed.
        val pendingField = TcpFlow::class.java.getDeclaredField("pending").apply { isAccessible = true }
        val pending = pendingField.get(flow) as java.util.ArrayDeque<*>
        val segment = pending.first()
        segment.javaClass.getDeclaredField("attempts").apply { isAccessible = true }.setInt(segment, 8)
        try {
            flow.tick(System.nanoTime() + 5_000_000_000L)
            fail("diagnostic exception should propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, closes.get())
        assertEquals(listOf(0x12, 0x14), packets.map { it.flags })
        flow.abort()
        assertEquals(1, closes.get())
        assertEquals(2, packets.size)
    }
}

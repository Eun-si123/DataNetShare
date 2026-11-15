package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class TcpFlowProtectFailureTest {
    private val key = FlowKey(0x0a000001, 51001, 0x08080808, 443)

    @Test fun rejectedProtectionClosesFlowWithoutConnecting() = verifyFailure { false }

    @Test fun thrownProtectionClosesFlowWithoutConnecting() = verifyFailure {
        throw IOException("synthetic protect failure")
    }

    private fun verifyFailure(protection: (Socket) -> Boolean) {
        val protects = AtomicInteger()
        val closes = AtomicInteger()
        val errors = mutableListOf<String>()
        val packets = mutableListOf<Ipv4Packet>()
        var capturedSocket: Socket? = null
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "unused",
            { socket -> protects.incrementAndGet(); capturedSocket = socket; protection(socket) },
            { packets += Ipv4Packet.parse(it)!! }, {}, {}, { closes.incrementAndGet() },
            initialSequence = 1000, onError = { errors += it })
        try {
            flow.start()
            flow.receive(Ipv4Packet(key, 6, 101, 1001, 0x10, 4096, ByteArray(0)))
            flow.openUpstream()
            assertEquals(1, protects.get())
            assertTrue("rejected socket must be closed", capturedSocket!!.isClosed)
            assertEquals("flow admission must be released once", 1, closes.get())
            assertEquals(listOf("Receiver local Wi-Fi/VPN socket error"), errors)
            assertEquals("SYN-ACK and RST only", listOf(0x12, 0x14), packets.map { it.flags })
            flow.close()
            assertEquals(1, closes.get())
        } finally { flow.close() }
    }
}

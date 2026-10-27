package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.assertEquals
import org.junit.Test

class TcpAppResetCleanupTest {
    private val key = FlowKey(0x0a000001, 55001, 0x08080808, 443)

    private fun flow(packets: MutableList<Ipv4Packet>, closed: () -> Unit): TcpFlow =
        TcpFlow(key, 100, "127.0.0.1", 1, "unused",
            { true }, { packets += Ipv4Packet.parse(it)!! },
            {}, {}, closed, initialSequence = 1000)

    @Test fun appResetBeforeHandshakeReleasesHalfOpenFlowWithoutEchoReset() {
        val packets = mutableListOf<Ipv4Packet>()
        var closes = 0
        val tcp = flow(packets) { closes++ }
        tcp.start()
        assertEquals(1, packets.size) // SYN-ACK

        tcp.receive(Ipv4Packet(key, 6, 101, 0, 0x04, 0, byteArrayOf()))
        assertEquals("app RST must immediately free the SOCKS flow slot", 1, closes)
        assertEquals("app RST must not provoke a second reset", 1, packets.size)
        tcp.retransmitSyn()
        assertEquals("closed flow must not retransmit SYN-ACK", 1, packets.size)
        tcp.close()
        assertEquals("cleanup must be idempotent", 1, closes)
    }

    @Test fun staleOrFutureResetSequenceCannotKillCurrentFlow() {
        val packets = mutableListOf<Ipv4Packet>()
        var closes = 0
        val tcp = flow(packets) { closes++ }
        tcp.start()

        for (sequence in listOf(100, 102, Int.MAX_VALUE)) {
            tcp.receive(Ipv4Packet(key, 6, sequence, 0, 0x04, 0, byteArrayOf()))
        }
        assertEquals("only an in-sequence RST may close this flow", 0, closes)
        assertEquals(1, packets.size)
        tcp.receive(Ipv4Packet(key, 6, 101, 0, 0x04, 0, byteArrayOf()))
        assertEquals(1, closes)
    }

    @Test fun appResetWithAckAfterHandshakeClosesEstablishedFlow() {
        val packets = mutableListOf<Ipv4Packet>()
        var closes = 0
        val tcp = flow(packets) { closes++ }
        tcp.start()
        tcp.receive(Ipv4Packet(key, 6, 101, 1001, 0x10, 65535, byteArrayOf()))
        val beforeReset = packets.size

        tcp.receive(Ipv4Packet(key, 6, 101, 1001, 0x14, 0, byteArrayOf()))
        assertEquals(1, closes)
        assertEquals("do not acknowledge a received RST", beforeReset, packets.size)
        tcp.close()
        assertEquals(1, closes)
    }
}

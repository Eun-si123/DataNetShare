package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.assertEquals
import org.junit.Test

/** IPv4 TCP sequence numbers are unsigned modulo 2^32, not signed Int counters. */
class TcpSequenceWrapTest {
    @Test fun uploadWrapAndRetransmissionDoNotDuplicateBytesOrAdvanceAck() {
        val key = FlowKey(0x0a000001, 50010, 0x08080808, 443)
        val replies = mutableListOf<Ipv4Packet>()
        var accepted = 0
        val flow = TcpFlow(key, initialAppSequence = -3,
            socksHost = "127.0.0.1", socksPort = 1, password = "unused",
            protect = { true }, writePacket = { replies.add(Ipv4Packet.parse(it)!!) },
            onRx = {}, onTx = { accepted += it }, onClose = {}, initialSequence = 9000)
        fun inbound(seq: Int, data: ByteArray = ByteArray(0)) =
            Ipv4Packet(key, 6, seq, 9001, if (data.isEmpty()) 0x10 else 0x18,
                4096, data)
        try {
            flow.start()
            assertEquals(-2, replies.last().acknowledgement)
            flow.receive(inbound(-2)) // ACK SYN-ACK, first app byte is 0xfffffffe.
            flow.receive(inbound(-2, byteArrayOf(1, 2, 3, 4)))
            assertEquals(2, replies.last().acknowledgement) // crossed 0xffffffff.
            flow.receive(inbound(-2, byteArrayOf(1, 2, 3, 4))) // duplicate.
            assertEquals(2, replies.last().acknowledgement)
            // Partly overlapping retransmission crosses the 32-bit boundary.
            flow.receive(inbound(0, byteArrayOf(3, 4, 5, 6)))
            assertEquals(4, replies.last().acknowledgement)
            assertEquals("duplicate segment must not be accepted twice", 6, accepted)
        } finally {
            flow.close()
        }
    }
}

package com.datanet.share.common

import com.datanet.share.receiver.TcpFlow
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TcpSynOptionsTest {
    private val key = FlowKey(0x0a090002, 33333, 0x08080808, 443)

    @Test fun synAckAdvertisesMss1400AndHasValidTcpChecksum() {
        val produced = LinkedBlockingQueue<ByteArray>()
        val flow = TcpFlow(key, 100, "127.0.0.1", 8282, "not-used",
            { true }, { produced.add(it) }, {}, {}, {}, initialSequence = 200)
        try {
            flow.start()
            val packet = produced.poll(1, TimeUnit.SECONDS)!!
            assertEquals(44, packet.size) // IPv4 20 + TCP 24 (4-byte MSS option)
            assertEquals(0x45, packet[0].toInt() and 0xff)
            assertEquals(6, (packet[32].toInt() and 0xff) ushr 4) // TCP data offset
            assertEquals(0x12, packet[33].toInt() and 0xff)
            assertArrayEquals(byteArrayOf(2, 4, 5, 0x78.toByte()), packet.copyOfRange(40, 44))
            assertEquals(0, IpPackets.checksum(packet.copyOfRange(0, 20)))

            // TCP checksum validation independently recomputes full RFC 793 pseudoheader.
            val pseudo = ByteBuffer.allocate(12 + packet.size - 20).order(ByteOrder.BIG_ENDIAN)
                .putInt(key.destination).putInt(key.source).put(0).put(6)
                .putShort((packet.size - 20).toShort())
                .put(packet, 20, packet.size - 20).array()
            assertEquals(0, IpPackets.checksum(pseudo))
            assertEquals(200, Ipv4Packet.parse(packet)!!.sequence)
            flow.tick(System.nanoTime() + 2_000_000_000L)
            val retransmitted = produced.poll(1, TimeUnit.SECONDS)!!
            assertArrayEquals("SYN retransmit should contain identical MSS", packet, retransmitted)
        } finally { flow.close() }
    }

    @Test fun windowScaleOnlyWhenSynNegotiatesAndCheckChecksum() {
        val packetQueue = LinkedBlockingQueue<ByteArray>()
        val flow = TcpFlow(key, 100, "127.0.0.1", 8282, "unused",
            { true }, { packetQueue.add(it) }, {}, {}, {}, initialSequence = 200,
            peerWindowScale = 7)
        try {
            flow.start()
            val synAck = packetQueue.poll(1, TimeUnit.SECONDS)!!
            assertEquals(48, synAck.size) // IPv4(20) TCP(28)
            assertEquals(7, (synAck[32].toInt() and 255) ushr 4)
            assertArrayEquals(byteArrayOf(2, 4, 5, 0x78, 3, 3, 2, 1),
                synAck.copyOfRange(40, 48))
            // SYN-ACK header window MUST NOT be scaled.
            assertEquals(65535, Ipv4Packet.parse(synAck)!!.window)
            val pseudo = ByteBuffer.allocate(12 + synAck.size - 20).order(ByteOrder.BIG_ENDIAN)
                .putInt(key.destination).putInt(key.source).put(0).put(6)
                .putShort((synAck.size - 20).toShort())
                .put(synAck, 20, synAck.size - 20).array()
            assertEquals(0, IpPackets.checksum(pseudo))
            val senderAck = Ipv4Packet(key, 6, 101, 201, 0x10, 1024, byteArrayOf())
            flow.receive(senderAck)
            // We advertise 262140 >> 2 = 65535 after negotiation.
            val receivedPayload = Ipv4Packet(key, 6, 101, 201, 0x18, 1024,
                ByteArray(1400) { 7 })
            flow.receive(receivedPayload)
            val ack = packetQueue.poll(1, TimeUnit.SECONDS)!!
            assertEquals(65535 - 350, Ipv4Packet.parse(ack)!!.window)
        } finally { flow.close() }
    }

    @Test fun aDataSegmentRemainsUnchangedAndOptionFree() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet = IpPackets.tcp(key, 100, 200, 0x18, 4096, payload)
        assertEquals(44, packet.size) // IPv4 20 + TCP 20 + payload 4
        assertEquals(5, (packet[32].toInt() and 0xff) ushr 4)
        assertArrayEquals(payload, Ipv4Packet.parse(packet)!!.payload)
    }

    @Test fun invalidTcpOptionsDoNotGenerateMalformedPackets() {
        try {
            IpPackets.tcp(key, 1, 2, 0x12, 4096, ByteArray(0), byteArrayOf(1, 2))
            fail("options must be aligned")
        } catch (_: IllegalArgumentException) {}
        try {
            IpPackets.tcp(key, 1, 2, 0x18, 4096, ByteArray(0), byteArrayOf(2, 4, 4, 0))
            fail("options must only appear during SYN")
        } catch (_: IllegalArgumentException) {}
    }
}

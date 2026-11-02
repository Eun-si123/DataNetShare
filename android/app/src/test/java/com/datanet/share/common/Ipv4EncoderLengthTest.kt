package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The IPv4 total-length field is unsigned 16-bit. These tests check wire
 * representability, NOT that jumbo packets fit the physical VPN link MTU.
 */
class Ipv4EncoderLengthTest {
    private val key = FlowKey(0x0a000001, 12345, 0x08080808, 443)

    private fun rejectsOverflow(encode: () -> ByteArray) {
        try {
            encode()
            fail("IPv4 length overflow must be rejected before serialization")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun udpLengthNeverWrapsPastUnsignedIpv4Limit() {
        val packet = IpPackets.udp(key, ByteArray(65_507) { 7 })
        assertEquals(65_535, packet.size)
        assertEquals(65_535, ByteBuffer.wrap(packet).getShort(2).toInt() and 0xffff)
        assertEquals(0, IpPackets.checksum(packet.copyOfRange(0, 20)))
        assertEquals(65_507, Ipv4Packet.parse(packet)!!.payload.size)
        rejectsOverflow { IpPackets.udp(key, ByteArray(65_508)) }
    }

    @Test fun tcpLengthNeverWrapsPastUnsignedIpv4Limit() {
        val packet = IpPackets.tcp(key, 1, 2, 0x18, 4096, ByteArray(65_495) { 3 })
        assertEquals(65_535, packet.size)
        assertEquals(65_495, Ipv4Packet.parse(packet)!!.payload.size)
        val pseudo = ByteBuffer.allocate(12 + packet.size - 20).order(ByteOrder.BIG_ENDIAN)
            .putInt(key.destination).putInt(key.source).put(0).put(6)
            .putShort((packet.size - 20).toShort())
            .put(packet, 20, packet.size - 20).array()
        assertEquals(0, IpPackets.checksum(pseudo))
        rejectsOverflow { IpPackets.tcp(key, 1, 2, 0x18, 4096, ByteArray(65_496)) }
    }

    @Test fun maximumTcpOptionsReducePermittedPayload() {
        val options = ByteArray(40) { 1 } // TCP NOPs, 4-byte aligned
        val packet = IpPackets.tcp(key, 1, 0, 0x12, 4096, ByteArray(65_455), options)
        assertEquals(65_535, packet.size)
        assertEquals(65_455, Ipv4Packet.parse(packet)!!.payload.size)
        assertArrayEquals(options, Ipv4Packet.parse(packet)!!.tcpOptions)
        rejectsOverflow { IpPackets.tcp(key, 1, 0, 0x12, 4096, ByteArray(65_456), options) }
    }
}

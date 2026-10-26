package com.datanet.share.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Semantic rejection tests at the IPv4 TUN input boundary. Fuzzing for parser
 * crashes alone cannot prove that malformed transport frames are discarded.
 * These are synthetic JVM packets, not real Android TUN/device verification.
 */
class Ipv4StructuralRejectionTest {
    private val key = FlowKey(0x0a000001, 12345, 0x08080808, 443)

    private fun changed(frame: ByteArray, offset: Int, value: Int): ByteArray =
        frame.copyOf().also { it[offset] = value.toByte() }

    @Test fun rejectsInvalidIpHeaderAndFragmentFlagsButAllowsDontFragment() {
        val tcp = IpPackets.tcp(key, 10, 0, 0x02, 4096, ByteArray(0))
        assertNotNull(Ipv4Packet.parse(tcp))
        assertNull(Ipv4Packet.parse(changed(tcp, 0, 0x65))) // IPv6 version nibble
        assertNull(Ipv4Packet.parse(changed(tcp, 0, 0x44))) // IHL shorter than 20
        assertNull(Ipv4Packet.parse(changed(tcp, 3, tcp.size - 1))) // total length too short for TCP
        assertNull(Ipv4Packet.parse(changed(tcp, 3, tcp.size + 1))) // truncated total
        for (flags in listOf(0x80, 0x20, 0x01)) {
            assertNull("fragment flags/offset 0x${flags.toString(16)}",
                Ipv4Packet.parse(changed(tcp, 6, flags)))
        }
        assertNotNull("DF is allowed", Ipv4Packet.parse(tcp))
    }

    @Test fun rejectsInvalidTcpDataOffsetsWithoutTreatingHeaderAsPayload() {
        val tcp = IpPackets.tcp(key, 10, 0, 0x18, 4096, byteArrayOf(1, 2, 3))
        assertNotNull(Ipv4Packet.parse(tcp))
        assertNull(Ipv4Packet.parse(changed(tcp, 32, 0x40))) // 16-byte TCP header
        assertNull(Ipv4Packet.parse(changed(tcp, 32, 0xf0))) // beyond total length
    }

    @Test fun rejectsUdpLengthMismatchesAndTruncatedReads() {
        val udp = IpPackets.udp(key, byteArrayOf(1, 2, 3, 4))
        assertEquals(4, Ipv4Packet.parse(udp)?.payload?.size)
        assertNull(Ipv4Packet.parse(changed(udp, 25, 7))) // shorter than UDP header
        assertNull(Ipv4Packet.parse(changed(udp, 25, 11))) // shorter than IP payload
        assertNull(Ipv4Packet.parse(changed(udp, 25, 13))) // longer than IP payload
        assertNull(Ipv4Packet.parse(udp, udp.size - 1))
        val extraBuffer = udp + byteArrayOf(99, 98, 97)
        assertEquals(4, Ipv4Packet.parse(extraBuffer, udp.size)?.payload?.size)
    }
}

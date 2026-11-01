package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

/**
 * IPv4 options change the start of the TCP/UDP header, independently of TCP
 * options. These are synthetic JVM packets, not physical TUN or app tests.
 */
class Ipv4HeaderOptionsBoundaryTest {
    private val key = FlowKey(0x0a000001, 34567, 0x08080808, 443)

    private fun withIpv4Options(original: ByteArray, options: ByteArray): ByteArray {
        require(options.size in 4..40 && options.size % 4 == 0)
        val frame = ByteArray(original.size + options.size)
        original.copyInto(frame, 0, 0, 20)
        options.copyInto(frame, 20)
        original.copyInto(frame, 20 + options.size, 20)
        frame[0] = (0x40 or ((20 + options.size) / 4)).toByte()
        val total = frame.size
        frame[2] = (total ushr 8).toByte()
        frame[3] = total.toByte()
        frame[10] = 0
        frame[11] = 0
        val checksum = IpPackets.checksum(frame.copyOfRange(0, 20 + options.size))
        frame[10] = (checksum ushr 8).toByte()
        frame[11] = checksum.toByte()
        assertEquals(0, IpPackets.checksum(frame.copyOfRange(0, 20 + options.size)))
        return frame
    }

    @Test fun tcpSynIpOptionsDoNotDisplaceTcpOptionsOrPayload() {
        val mss = byteArrayOf(2, 4, 5, 120)
        val payload = byteArrayOf(11, 22, 33)
        val original = IpPackets.tcp(key, 123, 456, 0x12, 4096, payload, mss)
        val frame = withIpv4Options(original, ByteArray(8) { 1 })
        val parsed = Ipv4Packet.parse(frame)!!

        assertEquals(6, parsed.protocol)
        assertEquals(123, parsed.sequence)
        assertEquals(456, parsed.acknowledgement)
        assertEquals(0x12, parsed.flags)
        assertEquals(4096, parsed.window)
        assertArrayEquals(mss, parsed.tcpOptions)
        assertEquals(1400, parsed.offeredMss())
        assertArrayEquals(payload, parsed.payload)
        assertEquals(key.destinationPort, parsed.key.sourcePort)
        assertEquals(key.sourcePort, parsed.key.destinationPort)
    }

    @Test fun maximumIpOptionsKeepUdpLengthAndReadBoundaryIntact() {
        val payload = byteArrayOf(4, 5, 6, 7, 8)
        val frame = withIpv4Options(IpPackets.udp(key, payload), ByteArray(40) { 1 })
        val buffer = frame + byteArrayOf(99, 98, 97)
        val parsed = Ipv4Packet.parse(buffer, buffer.size)!!

        assertEquals(17, parsed.protocol)
        assertArrayEquals(payload, parsed.payload)
        assertEquals(key.destinationPort, parsed.key.sourcePort)
        assertEquals(key.sourcePort, parsed.key.destinationPort)
        assertEquals(0, parsed.tcpOptions.size)
    }

    @Test fun claimedIpOptionsWithoutCompleteTransportHeaderAreRejected() {
        val udp = IpPackets.udp(key, byteArrayOf(1))
        val tcp = IpPackets.tcp(key, 1, 0, 0x02, 1024, byteArrayOf())
        for (frame in listOf(udp, tcp)) {
            val malformed = frame.copyOf()
            malformed[0] = 0x4f // Claims 60-byte IP header in a shorter frame.
            assertNull(Ipv4Packet.parse(malformed))
        }
        val valid = withIpv4Options(IpPackets.udp(key, byteArrayOf(1)), ByteArray(8) { 1 })
        assertNull(Ipv4Packet.parse(valid, valid.size - 1))
    }
}

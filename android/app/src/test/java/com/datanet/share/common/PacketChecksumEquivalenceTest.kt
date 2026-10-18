package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketChecksumEquivalenceTest {
    private val key = FlowKey(0x0a020304, 41234, 0x01020304, 443)

    @Test fun optimizedEncoderMatchesIndependentPseudoheaderChecksums() {
        for (length in listOf(0, 1, 2, 3, 7, 31, 1200, 1400, 1460)) {
            val payload = ByteArray(length) { (it * 17 + 3).toByte() }
            for (flags in listOf(0x18, 0x10)) {
                val tcp = IpPackets.tcp(key, 0x11223344, 0x55667788, flags, 65535, payload)
                assertEquals("IPv4 checksum length=$length", 0,
                    IpPackets.checksum(tcp.copyOfRange(0, 20)))
                val pseudo = ByteBuffer.allocate(12 + tcp.size - 20).order(ByteOrder.BIG_ENDIAN)
                    .putInt(key.destination).putInt(key.source).put(0).put(6)
                    .putShort((tcp.size - 20).toShort())
                    .put(tcp, 20, tcp.size - 20).array()
                assertEquals("TCP checksum length=$length", 0, IpPackets.checksum(pseudo))
                assertArrayEquals(payload, Ipv4Packet.parse(tcp)!!.payload)
            }
        }
    }

    @Test fun parsesMssOptionWithoutConfusingPaddingAndMalformedSyns() {
        val syn = IpPackets.tcp(key, 100, 0, 0x02, 4096, ByteArray(0),
            byteArrayOf(2, 4, 0x03, 0xE8.toByte()))
        assertEquals(1000, Ipv4Packet.parse(syn)!!.offeredMss())
        val padded = IpPackets.tcp(key, 100, 0, 0x02, 4096, ByteArray(0),
            byteArrayOf(1, 1, 2, 4, 0x02, 0x58, 0, 0))
        assertEquals(600, Ipv4Packet.parse(padded)!!.offeredMss())
        val missing = IpPackets.tcp(key, 100, 0, 0x02, 4096, ByteArray(0))
        assertNull(Ipv4Packet.parse(missing)!!.offeredMss())
        val malformed = IpPackets.tcp(key, 100, 0, 0x02, 4096, ByteArray(0),
            byteArrayOf(2, 3, 3, 0))
        assertNull(Ipv4Packet.parse(malformed)!!.offeredMss())
    }

    @Test fun parsesPeerWindowScaleWithLegacyAndMalformedFallbacks() {
        fun parse(option: ByteArray) = Ipv4Packet.parse(
            IpPackets.tcp(key, 100, 0, 0x02, 4096, ByteArray(0), option))!!
        assertEquals(7, parse(byteArrayOf(1, 3, 3, 7)).offeredWindowScale())
        assertEquals(14, parse(byteArrayOf(3, 3, 30, 1)).offeredWindowScale())
        assertEquals(0, parse(byteArrayOf(3, 3, 0, 1)).offeredWindowScale())
        assertNull(parse(byteArrayOf(2, 4, 5, 0x78.toByte())).offeredWindowScale())
        assertNull(parse(byteArrayOf(3, 2, 7, 1)).offeredWindowScale())
        assertNull(parse(byteArrayOf(3, 4, 7, 0)).offeredWindowScale())
        assertNull(parse(byteArrayOf(0, 3, 3, 7)).offeredWindowScale())
        assertNull(Ipv4Packet.parse(
            IpPackets.tcp(key, 100, 0, 0x10, 4096, ByteArray(0)))!!.offeredWindowScale())
    }

    @Test fun udpHeaderChecksumStillValid() {
        for (length in listOf(0, 1, 2, 1200, 1400)) {
            val packet = IpPackets.udp(key, ByteArray(length) { 0x55 })
            assertEquals(0, IpPackets.checksum(packet.copyOfRange(0, 20)))
            assertEquals(length, Ipv4Packet.parse(packet)!!.payload.size)
        }
    }
}

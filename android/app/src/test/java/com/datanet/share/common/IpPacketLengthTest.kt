package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class IpPacketLengthTest {
    private val key = FlowKey(0x0a000001, 20000, 0x08080808, 53)

    private fun assertMaxPacket(packet: ByteArray, payloadSize: Int) {
        assertEquals(65535, packet.size)
        val total = ((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
        assertEquals(packet.size, total)
        assertEquals(payloadSize, Ipv4Packet.parse(packet)?.payload?.size)
    }

    @Test fun udpAcceptsLastValidIpv4LengthButRejectsOverflow() {
        assertMaxPacket(IpPackets.udp(key, ByteArray(65507)), 65507)
        assertThrows(IllegalArgumentException::class.java) {
            IpPackets.udp(key, ByteArray(65508))
        }
    }

    @Test fun tcpAcceptsLastValidIpv4LengthButRejectsOverflow() {
        assertMaxPacket(IpPackets.tcp(key, 1, 0, 0x18, 1000, ByteArray(65495)), 65495)
        assertThrows(IllegalArgumentException::class.java) {
            IpPackets.tcp(key, 1, 0, 0x18, 1000, ByteArray(65496))
        }
    }

    @Test fun tcpSynOptionsCountTowardIpv4Length() {
        val mss = byteArrayOf(2, 4, 5, 120)
        assertMaxPacket(IpPackets.tcp(key, 1, 0, 0x12, 1000, ByteArray(65491), mss), 65491)
        assertThrows(IllegalArgumentException::class.java) {
            IpPackets.tcp(key, 1, 0, 0x12, 1000, ByteArray(65492), mss)
        }
    }
}

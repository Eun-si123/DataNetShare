package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

class ProtocolTest {
    @Test fun implicitSocksDestinationStringsHideHostAndPort() {
        val destination = Socks5.Address("example.test", 443)
        // The codec and map keys still require explicit host/port access.
        assertEquals("example.test", destination.host)
        assertEquals(443, destination.port)
        assertEquals(destination, destination.copy())
        assertEquals(destination.hashCode(), destination.copy().hashCode())

        // Accidental logging of a request or decoded UDP datagram must not
        // disclose the destination host or port via generated data-class text.
        val datagram = Socks5.Datagram(destination, byteArrayOf(1, 2, 3))
        for (debug in listOf(destination.toString(), datagram.toString())) {
            assertFalse(debug.contains("example.test"))
            assertFalse(debug.contains("443"))
            assertTrue(debug.contains("redacted"))
        }
    }

    @Test fun datagramRoundTripsIpv4Ipv6AndHostname() {
        listOf("192.168.77.1", "2001:db8::1234", "example.test").forEach { host ->
            val decoded = Socks5.decodeDatagram(Socks5.encodeDatagram(Socks5.Address(host, 65535), byteArrayOf(1, 2, 3)))!!
            if (host.contains(':')) assertArrayEquals(InetAddress.getByName(host).address, InetAddress.getByName(decoded.address.host).address)
            else assertEquals(host, decoded.address.host)
            assertEquals(65535, decoded.address.port); assertArrayEquals(byteArrayOf(1, 2, 3), decoded.payload)
        }
    }
    @Test fun malformedSocksDatagramsAreRejected() {
        val frame = Socks5.encodeDatagram(Socks5.Address("192.168.1.1", 53), ByteArray(0))
        for (length in 0 until 10) assertNull(Socks5.decodeDatagram(frame.copyOf(length)))
        frame[0] = 1; assertNull(Socks5.decodeDatagram(frame)); frame[0] = 0
        frame[2] = 1; assertNull(Socks5.decodeDatagram(frame))
    }
    @Test fun ipv4ParserChecksLengthsOffsetsAndFragments() {
        val key = FlowKey(0x0a000001, 1234, 0x01010101, 53)
        val bytes = IpPackets.udp(key, byteArrayOf(7))
        assertEquals(17, Ipv4Packet.parse(bytes)!!.protocol)
        assertEquals(0, IpPackets.checksum(bytes.copyOf(20)))
        assertNull(Ipv4Packet.parse(bytes, 10))
        val fragment = bytes.copyOf(); fragment[6] = 0x20; assertNull(Ipv4Packet.parse(fragment))
        val tcp = IpPackets.tcp(key, 1, 2, 0x18, 1024, byteArrayOf(1, 2))
        val parsed = Ipv4Packet.parse(tcp)!!
        assertEquals(1, parsed.sequence); assertEquals(2, parsed.acknowledgement)
        tcp[32] = 0x10; assertNull(Ipv4Packet.parse(tcp))
        ByteBuffer.wrap(bytes).putShort(24, 7); assertNull(Ipv4Packet.parse(bytes))
    }
    @Test fun ipv4ParserRejectsEveryTruncatedFrameAndFragmentOffset() {
        val key = FlowKey(0x0a000001, 1234, 0x01010101, 53)
        val frames = listOf(
            IpPackets.udp(key, byteArrayOf(7, 8, 9)),
            IpPackets.tcp(key, 1, 2, 0x18, 1024, byteArrayOf(7, 8, 9))
        )
        for (frame in frames) {
            assertNotNull(Ipv4Packet.parse(frame))
            for (count in 0 until frame.size) {
                assertNull("truncated protocol=${frame[9]} count=$count", Ipv4Packet.parse(frame, count))
            }
            assertNull(Ipv4Packet.parse(frame, frame.size + 1))
            // MF, nonzero offsets, and the reserved IPv4 flag (with or without DF)
            // must never be interpreted as a complete TCP/UDP datagram.
            for (fragmentBits in listOf(0x2000, 0x0001, 0x1fff, 0x3fff, 0x8000, 0xc000)) {
                val fragment = frame.copyOf()
                ByteBuffer.wrap(fragment).putShort(6, fragmentBits.toShort())
                assertNull("fragment flags/offset=$fragmentBits", Ipv4Packet.parse(fragment))
            }
        }
    }

    @Test fun implicitIpv4FlowStringsHidePeerEndpoints() {
        val key = FlowKey(0x0a112233, 47001, 0x0b445566, 49002)
        val flowDebug = key.toString()
        assertEquals("FlowKey(<redacted>)", flowDebug)
        val packet = Ipv4Packet.parse(IpPackets.tcp(key, 1, 2, 0x10, 1024, byteArrayOf(1)))!!
        val packetDebug = packet.toString()
        assertFalse(packetDebug.contains(key.source.toString()))
        assertFalse(packetDebug.contains(key.destination.toString()))
        assertFalse(packetDebug.contains("47001"))
        assertFalse(packetDebug.contains("49002"))
    }

    @Test fun receiverEndpointIsExplicitIpv4() {
        listOf("192.168.49.1", "10.0.0.2", "127.0.0.1").forEach { assertTrue(Endpoint.isIpv4(it)) }
        listOf("0.0.0.0", "256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", "localhost", "255.255.255.255").forEach { assertFalse(Endpoint.isIpv4(it)) }
    }
}

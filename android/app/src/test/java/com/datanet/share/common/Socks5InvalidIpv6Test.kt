package com.datanet.share.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress

class Socks5InvalidIpv6Test {
    @Test fun malformedIpv6CannotBeReinterpretedAsDomainName() {
        for (host in listOf("2001:db8::not-hex", "host:80", "::gg")) {
            val output = ByteArrayOutputStream()
            try {
                Socks5.writeAddress(output, Socks5.Address(host, 443))
                fail("Malformed IPv6 must not be sent as a SOCKS domain")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, output.size())
            }
        }
    }

    @Test fun validIpv6AndOrdinaryHostnameStillRoundTrip() {
        val ipv6 = ByteArrayOutputStream().also {
            Socks5.writeAddress(it, Socks5.Address("2001:db8::1", 443))
        }.toByteArray()
        assertEquals(4, ipv6[0].toInt() and 255)
        val decoded = Socks5.readAddress(DataInputStream(ByteArrayInputStream(ipv6)))
        assertArrayEquals(InetAddress.getByName("2001:db8::1").address,
            InetAddress.getByName(decoded.host).address)
        assertEquals(443, decoded.port)

        val domain = ByteArrayOutputStream().also {
            Socks5.writeAddress(it, Socks5.Address("example.test", 53))
        }.toByteArray()
        assertEquals(3, domain[0].toInt() and 255)
        assertEquals("example.test",
            Socks5.readAddress(DataInputStream(ByteArrayInputStream(domain))).host)
    }
}

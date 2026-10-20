package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class Socks5HostnameTest {
    @Test fun refusesHostnamesThatWouldBeSilentlyMangled() {
        for (host in listOf("tést.example", "bad host.example", "bad\nhost.example", "\u007f.example", "a".repeat(256))) {
            val bytes = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                Socks5.writeAddress(bytes, Socks5.Address(host, 443))
            }
            assertEquals("Invalid address must not write partial bytes", 0, bytes.size())
        }
    }

    @Test fun validAsciiHostnamesStillRoundTrip() {
        for (host in listOf("example.test", "xn--tst-bma.example", "a".repeat(255))) {
            val bytes = ByteArrayOutputStream()
            Socks5.writeAddress(bytes, Socks5.Address(host, 443))
            val parsed = Socks5.readAddress(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            assertEquals(host, parsed.host)
            assertEquals(443, parsed.port)
        }
    }

    @Test fun refusesMalformedIncomingHostnameBytes() {
        // SOCKS domain names must not be silently decoded with replacement chars.
        val valid = byteArrayOf(0, 0, 0, 3, 3, 97, 98, 99, 1, 0xbb.toByte())
        assertEquals("abc", Socks5.decodeDatagram(valid)?.address?.host)
        for (bad in listOf(0, 32, 127, 128, 255)) {
            val frame = byteArrayOf(0, 0, 0, 3, 1, bad.toByte(), 1, 0xbb.toByte())
            assertNull("Invalid SOCKS domain byte $bad must be rejected", Socks5.decodeDatagram(frame))
        }
    }
}

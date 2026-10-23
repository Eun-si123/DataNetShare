package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class Socks5HostnameTest {
    @Test fun refusesHostnamesThatWouldBeSilentlyMangled() {
        for (host in listOf("", "tést.example", "\ufffd.example", "\ud800.example", "\ud83d\ude00.example",
            "bad host.example", "bad\nhost.example", "bad\u0000host.example",
            "\u007f.example", "a".repeat(256))) {
            val bytes = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                Socks5.writeAddress(bytes, Socks5.Address(host, 443))
            }
            assertEquals("Invalid address must not write partial bytes", 0, bytes.size())
        }
    }

    @Test fun validAsciiHostnamesStillRoundTrip() {
        for (host in listOf("a", "example.test", "xn--tst-bma.example", "a".repeat(255))) {
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

    @Test fun tcpAddressReaderRejectsMalformedDomainFields() {
        for (domain in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(32),
            byteArrayOf(127), byteArrayOf(128.toByte()), byteArrayOf(255.toByte()))) {
            val frame = byteArrayOf(3, domain.size.toByte()) + domain + byteArrayOf(1, 0xbb.toByte())
            assertThrows(IOException::class.java) {
                Socks5.readAddress(DataInputStream(ByteArrayInputStream(frame)))
            }
        }
        // A declared domain length must not consume a truncated name or missing port.
        for (frame in listOf(byteArrayOf(3, 3, 97), byteArrayOf(3, 1, 97, 1))) {
            assertThrows(IOException::class.java) {
                Socks5.readAddress(DataInputStream(ByteArrayInputStream(frame)))
            }
        }
    }

    @Test fun canonicalIpv4UsesBinaryTypeWithoutNameResolution() {
        for (host in listOf("0.0.0.0", "192.0.2.1", "255.255.255.255")) {
            val output = ByteArrayOutputStream()
            Socks5.writeAddress(output, Socks5.Address(host, 443))
            assertEquals(1, output.toByteArray()[0].toInt() and 255)
            val decoded = Socks5.readAddress(DataInputStream(ByteArrayInputStream(output.toByteArray())))
            assertEquals(host, decoded.host)
            assertEquals(443, decoded.port)
        }
    }

    @Test fun ambiguousNumericHostsFailBeforeWritingAnyBytes() {
        for (host in listOf("1.2.3", "127.1", "2130706433", "01.2.3.4", "1.2.3.999",
            "...", "1.2.3.4.", "1.2.3.04", "999.0.0.0", "123", ".", "0", "001.2.3.4")) {
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                Socks5.writeAddress(output, Socks5.Address(host, 443))
            }
            assertEquals(0, output.size())
        }
    }

    @Test fun senderRejectsAmbiguousNumericDomainFromOtherSocksClients() {
        for (host in listOf("1.2.3", "127.1", "2130706433", "01.2.3.4", "1.2.3.999",
            "0", "001.2.3.4")) {
            val addressFrame = byteArrayOf(3, host.length.toByte()) +
                host.toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 0xbb.toByte())
            assertThrows(IOException::class.java) {
                Socks5.readAddress(DataInputStream(ByteArrayInputStream(addressFrame)))
            }
            assertNull(Socks5.decodeDatagram(byteArrayOf(0, 0, 0) + addressFrame))
        }
    }

    @Test fun numericDnsLabelsWithAlphabeticSuffixStillRoundTrip() {
        for (host in listOf("123.example", "1.2.3.example")) {
            val output = ByteArrayOutputStream()
            Socks5.writeAddress(output, Socks5.Address(host, 443))
            assertEquals(3, output.toByteArray()[0].toInt() and 255)
            assertEquals(host, Socks5.readAddress(
                DataInputStream(ByteArrayInputStream(output.toByteArray()))).host)
        }
    }
}

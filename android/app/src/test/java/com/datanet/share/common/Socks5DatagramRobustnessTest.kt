package com.datanet.share.common

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * UDP frames are supplied by a peer and must never terminate a relay worker
 * through an unchecked decoder exception. This is parser-only verification,
 * not a physical VPN/UDP interoperability or throughput test.
 */
class Socks5DatagramRobustnessTest {
    @Test fun truncatedHeadersAndUnsupportedAddressTypesFailClosed() {
        val valid = Socks5.encodeDatagram(
            Socks5.Address("192.0.2.1", 53), byteArrayOf(1, 2, 3))
        assertNotNull(Socks5.decodeDatagram(valid))
        for (length in 0 until 10) {
            assertNull("truncated IPv4 frame length=$length",
                Socks5.decodeDatagram(valid.copyOf(length)))
        }
        for (index in 0..2) {
            val changed = valid.copyOf()
            changed[index] = 1
            assertNull("nonzero reserved/fragment field at index=$index",
                Socks5.decodeDatagram(changed))
        }
        for (type in listOf(0, 2, 5, 255)) {
            val changed = valid.copyOf()
            changed[3] = type.toByte()
            assertNull("unsupported SOCKS address type=$type",
                Socks5.decodeDatagram(changed))
        }
        // A domain-length byte cannot claim more bytes than the frame carries.
        assertNull(Socks5.decodeDatagram(byteArrayOf(0, 0, 0, 3, 4, 97, 98)))
    }

    @Test fun everySingleByteMutationOfValidAddressFormsIsExceptionSafe() {
        val originals = listOf(
            Socks5.encodeDatagram(Socks5.Address("192.0.2.1", 53), byteArrayOf(1, 2, 3)),
            Socks5.encodeDatagram(Socks5.Address("2001:db8::1", 443), byteArrayOf(4, 5)),
            Socks5.encodeDatagram(Socks5.Address("example.test", 5353), byteArrayOf(6))
        )
        for ((frameIndex, original) in originals.withIndex()) {
            for (index in original.indices) {
                for (value in 0..255) {
                    val mutated = original.copyOf()
                    mutated[index] = value.toByte()
                    val decoded = try {
                        Socks5.decodeDatagram(mutated)
                    } catch (failure: Throwable) {
                        throw AssertionError(
                            "decoder threw: frame=$frameIndex offset=$index byte=$value", failure)
                    }
                    if (decoded != null) {
                        assertTrue("payload exceeds input frame",
                            decoded.payload.size <= mutated.size)
                    }
                }
            }
        }
    }

    @Test fun boundedRandomMalformedFramesNeverThrow() {
        val random = Random(0x5A0C5L)
        repeat(4096) { case ->
            val bytes = ByteArray(random.nextInt(256))
            random.nextBytes(bytes)
            val decoded = try {
                Socks5.decodeDatagram(bytes)
            } catch (failure: Throwable) {
                throw AssertionError("decoder threw for generated case=$case", failure)
            }
            if (decoded != null) {
                assertTrue(decoded.address.port in 0..65535)
                assertTrue(decoded.payload.size <= bytes.size)
            }
        }
    }
}

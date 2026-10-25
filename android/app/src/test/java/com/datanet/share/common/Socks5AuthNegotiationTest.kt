package com.datanet.share.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class Socks5AuthNegotiationTest {
    @Test fun missingPasswordCannotOfferUnauthenticatedSocks() {
        val sent = ByteArrayOutputStream()
        try {
            Socks5.authenticateClient(DataInputStream(ByteArrayInputStream(byteArrayOf(5, 0))), sent, "")
            fail("Empty credentials must be rejected before negotiation")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(), sent.toByteArray())
    }

    @Test fun oversizedPasswordIsRejectedBeforeAnyHandshakeBytes() {
        val sent = ByteArrayOutputStream()
        try {
            Socks5.authenticateClient(DataInputStream(ByteArrayInputStream(byteArrayOf(5, 2))), sent, "x".repeat(256))
            fail("Oversized credentials must be rejected before negotiation")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(), sent.toByteArray())
    }

    @Test fun validClientOffersOnlyPasswordAuthentication() {
        val sent = ByteArrayOutputStream()
        Socks5.authenticateClient(
            DataInputStream(ByteArrayInputStream(byteArrayOf(5, 2, 1, 0))),
            sent, "example-test-password"
        )
        val bytes = sent.toByteArray()
        assertArrayEquals(byteArrayOf(5, 1, 2), bytes.copyOfRange(0, 3))
        assertEquals(1, bytes[3].toInt())
        assertEquals(Socks5.USERNAME.length, bytes[4].toInt() and 255)
    }

    @Test fun clientRejectsServerNoAuthenticationSelection() {
        val sent = ByteArrayOutputStream()
        try {
            Socks5.authenticateClient(
                DataInputStream(ByteArrayInputStream(byteArrayOf(5, 0))),
                sent, "example-test-password"
            )
            fail("No-auth selection must be rejected")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(5, 1, 2), sent.toByteArray())
    }

    @Test fun senderRejectsUnauthenticatedClientMethod() {
        val sent = ByteArrayOutputStream()
        try {
            Socks5.authenticateServer(
                DataInputStream(ByteArrayInputStream(byteArrayOf(5, 1, 0))),
                sent, "example-test-password"
            )
            fail("Sender must refuse no-auth-only clients")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(5, 0xff.toByte()), sent.toByteArray())
    }
}

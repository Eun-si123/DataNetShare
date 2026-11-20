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
    @Test fun cancellationAfterMethodReplyDoesNotSendUsernameOrPassword() {
        val sent = ByteArrayOutputStream()
        var gateCalls = 0
        try {
            Socks5.authenticateClient(
                DataInputStream(ByteArrayInputStream(byteArrayOf(5, 2))),
                sent, "private-synthetic-password", beforeCredentials = {
                    gateCalls++
                    false
                }
            )
            fail("cancelled authentication must stop before credential write")
        } catch (_: IOException) { }
        assertEquals(1, gateCalls)
        assertArrayEquals(byteArrayOf(5, 1, 2), sent.toByteArray())
    }

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

    @Test fun senderRejectsWrongPasswordWithoutAcceptingRequest() {
        val password = "synthetic-test-password"
        val wrong = "synthetic-wrong-password".toByteArray(Charsets.UTF_8)
        val username = Socks5.USERNAME.toByteArray(Charsets.UTF_8)
        val offered = byteArrayOf(5, 1, 2, 1, username.size.toByte()) + username +
            byteArrayOf(wrong.size.toByte()) + wrong
        val sent = ByteArrayOutputStream()
        try {
            Socks5.authenticateServer(DataInputStream(ByteArrayInputStream(offered)), sent, password)
            fail("Wrong password must reject authentication")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(5, 2, 1, 1), sent.toByteArray())
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

    @Test fun senderRejectsWrongPasswordWithExplicitFailureReply() {
        val request = byteArrayOf(5, 1, 2, 1, 7) +
            "datanet".toByteArray() + byteArrayOf(5) + "wrong".toByteArray()
        val sent = ByteArrayOutputStream()
        try {
            Socks5.authenticateServer(
                DataInputStream(ByteArrayInputStream(request)), sent, "expected-test-password"
            )
            fail("Wrong password must not authenticate")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(5, 2, 1, 1), sent.toByteArray())
    }

    @Test fun senderRejectsWrongUsernameEvenWithCorrectPassword() {
        val password = "expected-test-password"
        val request = byteArrayOf(5, 1, 2, 1, 5) +
            "other".toByteArray() + byteArrayOf(password.length.toByte()) + password.toByteArray()
        val sent = ByteArrayOutputStream()
        try {
            Socks5.authenticateServer(
                DataInputStream(ByteArrayInputStream(request)), sent, password
            )
            fail("Wrong username must not authenticate")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(5, 2, 1, 1), sent.toByteArray())
    }

}

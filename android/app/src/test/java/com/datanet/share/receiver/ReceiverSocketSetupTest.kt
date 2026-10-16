package com.datanet.share.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class ReceiverSocketSetupTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test fun tcpSocketIsBoundBeforeProtectionAndUnconnectedUntilAfter() {
        Socket().use { socket ->
            val steps = mutableListOf<String>()
            val prepared = ReceiverSocketSetup.bindThenProtect(socket, {
                assertFalse(it.isConnected)
                it.bind(InetSocketAddress(loopback, 0))
                steps += "bind"
            }, {
                assertTrue(it.isBound)
                assertFalse(it.isConnected)
                steps += "protect"
                true
            })
            assertTrue(prepared)
            assertEquals(listOf("bind", "protect"), steps)
            assertFalse(socket.isConnected)
        }
    }

    @Test fun udpSocketIsBoundBeforeProtectionAndUnconnectedUntilAfter() {
        DatagramSocket(null).use { socket ->
            val steps = mutableListOf<String>()
            val prepared = ReceiverSocketSetup.bindThenProtect(socket, {
                it.bind(InetSocketAddress(loopback, 0))
                steps += "bind"
            }, {
                assertTrue(it.isBound)
                assertFalse(it.isConnected)
                steps += "protect"
                true
            })
            assertTrue(prepared)
            assertEquals(listOf("bind", "protect"), steps)
        }
    }

    @Test fun protectionFailureIsNotIgnored() {
        Socket().use { socket ->
            assertFalse(ReceiverSocketSetup.bindThenProtect(socket, {
                it.bind(InetSocketAddress(loopback, 0))
            }, { false }))
            assertFalse(socket.isConnected)
        }
    }

    @Test fun bindingFailureDoesNotAttemptProtection() {
        Socket().use { socket ->
            var protectCalled = false
            try {
                ReceiverSocketSetup.bindThenProtect(socket, {
                    throw IOException("No local link")
                }, {
                    protectCalled = true
                    true
                })
                throw AssertionError("Expected binding failure")
            } catch (expected: IOException) {
                assertEquals("No local link", expected.message)
            }
            assertFalse(protectCalled)
        }
    }
}

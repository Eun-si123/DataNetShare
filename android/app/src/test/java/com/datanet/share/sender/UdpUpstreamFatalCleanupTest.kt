package com.datanet.share.sender

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** A fatal binding failure must not leak the allocated UDP socket. */
class UdpUpstreamFatalCleanupTest {
    @Test fun fatalBindingFailureClosesSocketAndPreservesOriginalError() {
        val socket = DatagramSocket()
        val failure = AssertionError("synthetic native failure")
        val destination = InetSocketAddress(InetAddress.getLoopbackAddress(), 9)

        val thrown = assertThrows(AssertionError::class.java) {
            UdpUpstreamSocketSetup.prepare(socket, destination) { throw failure }
        }

        assertSame(failure, thrown)
        assertTrue(socket.isClosed)
    }
}

package com.datanet.share.sender

import android.system.ErrnoException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class UdpUpstreamSocketSetupTest {
    private val destination = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9)

    @Test fun permissionDenialClosesSocketAndPropagatesOriginalFailure() {
        val socket = DatagramSocket()
        val denied = SecurityException("synthetic permission denial")
        val thrown = assertThrows(SecurityException::class.java) {
            UdpUpstreamSocketSetup.prepare(socket, destination) { throw denied }
        }
        assertSame(denied, thrown)
        assertTrue(socket.isClosed)
    }

    @Test fun networkBindingFailureClosesSocketForRetry() {
        val socket = DatagramSocket()
        val failed = IOException("synthetic network unavailable")
        val thrown = assertThrows(IOException::class.java) {
            UdpUpstreamSocketSetup.prepare(socket, destination) { throw failed }
        }
        assertSame(failed, thrown)
        assertTrue(socket.isClosed)
    }

    private fun syntheticErrno(value: Int): ErrnoException =
        ErrnoException("bindSocket", value).also { failure ->
            // Android's mockable unit-test jar leaves platform errno fields at zero.
            ErrnoException::class.java.getField("errno").setInt(failure, value)
        }

    @Test fun errnoPermissionDenialIsNotRetryable() {
        for (errno in listOf(1, 13)) {
            val failure = java.net.SocketException("synthetic bind error")
            failure.initCause(syntheticErrno(errno))
            assertTrue(isUdpBindingPermissionDenied(failure))
        }
    }

    @Test fun ordinarySocketFailuresRemainRetryable() {
        assertFalse(isUdpBindingPermissionDenied(java.net.SocketException("network unreachable")))
        val failure = java.net.SocketException("synthetic bind error")
        failure.initCause(syntheticErrno(101))
        assertFalse(isUdpBindingPermissionDenied(failure))
    }

    @Test fun successfulPreparationKeepsConnectedSocketOpen() {
        DatagramSocket().use { socket ->
            var bound = false
            UdpUpstreamSocketSetup.prepare(socket, destination) { bound = true }
            assertTrue(bound)
            assertFalse(socket.isClosed)
            assertTrue(socket.isConnected)
            assertEquals(destination, socket.remoteSocketAddress)
        }
    }
}

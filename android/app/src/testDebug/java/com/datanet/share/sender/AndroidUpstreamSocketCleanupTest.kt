package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress

class AndroidUpstreamSocketCleanupTest {
    private class FailingSocket(
        private val connectFailure: Exception,
        private val closeFailure: IOException? = null
    ) : Socket() {
        var closeCalls = 0
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            throw connectFailure
        }
        override fun close() {
            closeCalls++
            if (closeFailure != null) throw closeFailure
            super.close()
        }
    }

    @Test fun closesSocketAndPreservesIOExceptionForAddressFallback() {
        val failure = IOException("synthetic connect failure")
        val socket = FailingSocket(failure)
        try {
            connectSocketOrClose(socket, InetSocketAddress("127.0.0.1", 1), 100)
            fail("connect must fail")
        } catch (caught: IOException) {
            assertSame(failure, caught)
        }
        assertEquals(1, socket.closeCalls)
        assertTrue(socket.isClosed)
    }

    @Test fun closesSocketAndPropagatesPermissionFailureWithoutRetrying() {
        val failure = SecurityException("synthetic permission failure")
        val socket = FailingSocket(failure)
        try {
            connectSocketOrClose(socket, InetSocketAddress("127.0.0.1", 1), 100)
            fail("permission failure must propagate")
        } catch (caught: SecurityException) {
            assertSame(failure, caught)
        }
        assertEquals(1, socket.closeCalls)
        assertTrue(socket.isClosed)
    }

    @Test fun cleanupFailureDoesNotHideOriginalConnectFailure() {
        val failure = SecurityException("synthetic connect failure")
        val cleanup = IOException("synthetic close failure")
        val socket = FailingSocket(failure, cleanup)
        try {
            connectSocketOrClose(socket, InetSocketAddress("127.0.0.1", 1), 100)
            fail("connect must fail")
        } catch (caught: SecurityException) {
            assertSame(failure, caught)
            assertEquals(1, caught.suppressed.size)
            assertSame(cleanup, caught.suppressed.single())
        }
        assertEquals(1, socket.closeCalls)
    }

    @Test fun successfulConnectionRemainsOpenForCaller() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket().use { socket ->
                val connected = connectSocketOrClose(
                    socket, InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 1000)
                assertSame(socket, connected)
                assertTrue(connected.isConnected)
                assertFalse(connected.isClosed)
            }
        }
    }
}

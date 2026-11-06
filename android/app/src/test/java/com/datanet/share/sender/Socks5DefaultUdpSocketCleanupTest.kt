package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/** Tests the real default SOCKS5 Upstream.openUdp implementation, not AndroidUpstream. */
class Socks5DefaultUdpSocketCleanupTest {
    private class TestSocket(
        private val connectFailure: Throwable? = null,
        private val closeFailure: Throwable? = null
    ) : DatagramSocket(null as SocketAddress?) {
        var closeCalls = 0
        override fun connect(addr: SocketAddress?) {
            if (connectFailure != null) throw connectFailure
            super.connect(addr)
        }
        override fun close() {
            closeCalls++
            try { if (closeFailure != null) throw closeFailure }
            finally { super.close() } // Never leak this real test FD.
        }
    }

    private class TestUpstream(private val socket: DatagramSocket) : Socks5Server.Upstream {
        override fun connect(host: String, port: Int): Socket = error("TCP not used")
        override fun resolve(host: String): Array<InetAddress> = arrayOf(InetAddress.getLoopbackAddress())
        override fun datagram(): DatagramSocket = socket
    }

    @Test fun ioFailureClosesNewlyCreatedSocket() {
        val failure = IOException("synthetic UDP connect failure")
        val socket = TestSocket(failure)
        try { TestUpstream(socket).openUdp("127.0.0.1", 9); fail("expected I/O failure") }
        catch (caught: IOException) { assertSame(failure, caught) }
        assertEquals(1, socket.closeCalls)
        assertTrue(socket.isClosed)
    }

    @Test fun permissionFailureClosesSocketAndPreservesOriginalFailure() {
        val failure = SecurityException("synthetic route permission error")
        val socket = TestSocket(failure)
        try { TestUpstream(socket).openUdp("127.0.0.1", 9); fail("expected permission failure") }
        catch (caught: SecurityException) { assertSame(failure, caught) }
        assertEquals(1, socket.closeCalls)
        assertTrue(socket.isClosed)
    }

    @Test fun nativeResourceFailureMustCloseAndNotBeConvertedToIoFailure() {
        val failure = OutOfMemoryError("synthetic native resource limit")
        val socket = TestSocket(failure)
        try { TestUpstream(socket).openUdp("127.0.0.1", 9); fail("expected fatal error") }
        catch (caught: OutOfMemoryError) { assertSame(failure, caught) }
        assertEquals("socket must be closed after fatal connect error", 1, socket.closeCalls)
        assertTrue(socket.isClosed)
    }

    @Test fun secondaryCloseFailureMustNotConcealConnectFailure() {
        val failure = IOException("synthetic UDP connect failure")
        val cleanup = AssertionError("synthetic close error")
        val socket = TestSocket(failure, cleanup)
        try { TestUpstream(socket).openUdp("127.0.0.1", 9); fail("expected failure") }
        catch (caught: IOException) {
            assertSame(failure, caught)
            assertEquals(1, caught.suppressed.size)
            assertSame(cleanup, caught.suppressed.single())
        }
        assertEquals(1, socket.closeCalls)
        assertTrue(socket.isClosed)
    }

    @Test fun successKeepsSocketConnectedAndOwnedByCaller() {
        val socket = TestSocket()
        socket.use {
            val result = TestUpstream(socket).openUdp("127.0.0.1", 9)
            assertSame(socket, result.socket)
            assertTrue(socket.isConnected)
            assertFalse(socket.isClosed)
            assertEquals(InetSocketAddress(InetAddress.getLoopbackAddress(), 9), result.destination)
        }
    }
}

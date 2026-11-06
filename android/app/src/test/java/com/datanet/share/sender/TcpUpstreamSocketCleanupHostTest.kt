package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress

/** Exercise the production TCP socket owner without Android framework mocks. */
class TcpUpstreamSocketCleanupHostTest {
    private class FailingSocket(private val connectFailure: Throwable,
                                private val closeFailure: Throwable? = null) : Socket() {
        var closes = 0
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            throw connectFailure
        }
        override fun close() {
            closes++
            if (closeFailure != null) throw closeFailure
            super.close()
        }
    }

    private val destination = InetSocketAddress("127.0.0.1", 9)

    @Test fun ioFailureClosesBeforeAddressFailover() {
        val error = IOException("synthetic connect failure")
        val socket = FailingSocket(error)
        try { connectSocketOrClose(socket, destination, 100); fail("expected failure") }
        catch (caught: IOException) { assertSame(error, caught) }
        assertEquals(1, socket.closes)
        assertTrue(socket.isClosed)
    }

    @Test fun permissionErrorClosesAndKeepsOriginalFailureType() {
        val error = SecurityException("synthetic denied")
        val socket = FailingSocket(error)
        try { connectSocketOrClose(socket, destination, 100); fail("expected failure") }
        catch (caught: SecurityException) { assertSame(error, caught) }
        assertEquals(1, socket.closes)
        assertTrue(socket.isClosed)
    }

    @Test fun nativeResourceExhaustionReleasesSocketAndRethrowsOriginal() {
        val error = OutOfMemoryError("synthetic native resource shortage")
        val socket = FailingSocket(error)
        try { connectSocketOrClose(socket, destination, 100); fail("expected failure") }
        catch (caught: OutOfMemoryError) { assertSame(error, caught) }
        assertEquals(1, socket.closes)
        assertTrue(socket.isClosed)
    }

    @Test fun cleanupErrorIsSuppressedWithoutHidingNativeResourceFailure() {
        val error = OutOfMemoryError("synthetic native resource shortage")
        val cleanupError = AssertionError("synthetic close failure")
        val socket = FailingSocket(error, cleanupError)
        try { connectSocketOrClose(socket, destination, 100); fail("expected failure") }
        catch (caught: OutOfMemoryError) {
            assertSame(error, caught)
            assertEquals(1, caught.suppressed.size)
            assertSame(cleanupError, caught.suppressed.single())
        }
        assertEquals(1, socket.closes)
    }

    @Test fun successfulConnectionRemainsOpenAndOwnedByCaller() {
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

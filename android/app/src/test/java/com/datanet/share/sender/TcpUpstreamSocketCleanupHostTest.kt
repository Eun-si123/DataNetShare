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
    /** Inject a connect error and optionally a close error without leaking the test FD. */
    private class FailingSocket(private val connectFailure: Throwable,
                                private val closeFailure: Throwable? = null) : Socket() {
        var closes = 0
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            throw connectFailure
        }
        override fun close() {
            closes++
            try {
                if (closeFailure != null) throw closeFailure
            } finally {
                // The failure is synthetic; never leave an actual test socket open.
                super.close()
            }
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
        assertTrue("synthetic close failure must not leak the test socket", socket.isClosed)
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
    @Test fun factoryIoFailureRetriesNextAddress() {
        val addresses = arrayOf(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress())
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var calls = 0
            connectAcrossAddresses(addresses, server.localPort) {
                calls++
                if (calls == 1) throw IOException("synthetic factory failure")
                Socket()
            }.use { connected -> assertTrue(connected.isConnected) }
            assertEquals(2, calls)
        }
    }

    @Test fun factoryPermissionFailureNeverRetries() {
        var calls = 0
        val failure = SecurityException("synthetic denied")
        try {
            connectAcrossAddresses(arrayOf(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress()), 9) {
                calls++
                throw failure
            }
            fail("expected failure")
        } catch (caught: SecurityException) { assertSame(failure, caught) }
        assertEquals(1, calls)
    }

}

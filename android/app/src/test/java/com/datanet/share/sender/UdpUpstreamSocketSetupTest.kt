package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress

class UdpUpstreamSocketSetupTest {
    private val destination = InetSocketAddress("127.0.0.1", 9)

    @Test fun ioFailureClosesSocketAndPreservesOriginalException() {
        val socket = DatagramSocket()
        val failure = IOException("synthetic setup failure")
        try {
            UdpUpstreamSocketSetup.prepare(socket, destination) { throw failure }
            fail("failed setup must throw")
        } catch (caught: IOException) { assertSame(failure, caught) }
        assertTrue("failed socket must be closed", socket.isClosed)
    }

    @Test fun permissionDeniedClosesSocketAndDoesNotBecomeIoFailure() {
        val socket = DatagramSocket()
        val failure = SecurityException("synthetic route denial")
        try {
            UdpUpstreamSocketSetup.prepare(socket, destination) { throw failure }
            fail("permission failure must propagate")
        } catch (caught: SecurityException) { assertSame(failure, caught) }
        assertTrue("denied socket must be closed", socket.isClosed)
    }

    @Test fun virtualMachineErrorReclaimsSocketWithoutHidingOriginal() {
        val socket = DatagramSocket()
        val failure = OutOfMemoryError("synthetic native resource limit")
        try {
            UdpUpstreamSocketSetup.prepare(socket, destination) { throw failure }
            fail("fatal setup failure must propagate")
        } catch (caught: OutOfMemoryError) { assertSame(failure, caught) }
        assertTrue("fatal setup failure must close socket", socket.isClosed)
    }

    @Test fun successfulSetupKeepsSocketOpenAndConnected() {
        DatagramSocket().use { socket ->
            var bound = false
            UdpUpstreamSocketSetup.prepare(socket, destination) { bound = true }
            assertTrue(bound)
            assertFalse("connected socket belongs to caller", socket.isClosed)
            assertTrue(socket.isConnected)
            assertEquals(destination, socket.remoteSocketAddress)
        }
    }
}

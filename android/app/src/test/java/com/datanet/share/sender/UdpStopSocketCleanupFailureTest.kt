package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stop must reclaim all client sockets even if one UDP upstream close fails. */
class UdpStopSocketCleanupFailureTest {
    private open class GatedUdpSocket : DatagramSocket() {
        val inReceive = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override fun receive(packet: DatagramPacket) {
            inReceive.countDown()
            // Only a real close may release this receive worker: a timeout
            // would let the test pass without exercising Stop cleanup.
            closed.await()
            throw SocketException("socket closed by Stop")
        }
        override fun close() {
            super.close()
            closed.countDown()
        }
    }

    private class FailingCloseUdpSocket : GatedUdpSocket() {
        private val throwOnce = AtomicBoolean(true)
        override fun close() {
            // Always release the real descriptor, even when the test throws.
            super.close()
            if (throwOnce.compareAndSet(true, false)) {
                throw IllegalStateException("synthetic UDP close failure")
            }
        }
    }

    @Test(timeout = 20_000)
    fun throwingUdpCloseCannotPreventOtherClientCleanup() {
        val upstreamSocket = LinkedBlockingQueue<GatedUdpSocket>()
        val upstream = object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket = error("TCP upstream not used")
            override fun resolve(host: String): Array<InetAddress> = arrayOf(InetAddress.getLoopbackAddress())
            override fun datagram(): DatagramSocket = error("not used")
            override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                assertEquals("127.0.0.1", host)
                assertTrue("only two fixture destinations", port == 9 || port == 10)
                val destination = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                val socket = if (port == 9) FailingCloseUdpSocket() else GatedUdpSocket()
                socket.connect(destination)
                upstreamSocket.add(socket)
                return Socks5Server.UdpConnection(socket, destination)
            }
        }
        val server = Socks5Server("127.0.0.1", 0, "test-only-password", upstream,
            socketPollMillis = 100)
        server.start()
        try {
            val host = InetAddress.getLoopbackAddress()
            Socket(host, server.boundPort).use { control ->
                Socket(host, server.boundPort).use { otherClient ->
                    control.soTimeout = 3000
                    otherClient.soTimeout = 3000
                    val input = DataInputStream(control.getInputStream())
                    Socks5.authenticateClient(input, control.getOutputStream(), "test-only-password")
                    val relay = Socks5.request(input, control.getOutputStream(), 3,
                        Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { sender ->
                        val endpoint = InetSocketAddress(relay.host, relay.port)
                        for (destinationPort in listOf(9, 10)) {
                            val frame = Socks5.encodeDatagram(
                                Socks5.Address("127.0.0.1", destinationPort),
                                byteArrayOf(destinationPort.toByte()))
                            sender.send(DatagramPacket(frame, frame.size, endpoint))
                        }
                        val first = upstreamSocket.poll(3, TimeUnit.SECONDS)
                        val second = upstreamSocket.poll(3, TimeUnit.SECONDS)
                        assertNotNull("first authenticated UDP upstream did not start", first)
                        assertNotNull("second authenticated UDP upstream did not start", second)
                        assertTrue("first worker never entered receive",
                            first!!.inReceive.await(3, TimeUnit.SECONDS))
                        assertTrue("second worker never entered receive",
                            second!!.inReceive.await(3, TimeUnit.SECONDS))
                        server.stop() // Old code throws from one socket and can skip the other.
                        assertEquals(0, server.boundPort)
                        assertTrue("throwing close still releases its real socket", first.isClosed)
                        assertTrue("another upstream must close regardless of iteration order",
                            second.isClosed)
                        assertEquals("other active client must be closed during Stop",
                            -1, otherClient.getInputStream().read())
                        assertEquals("UDP control socket must also close", -1,
                            control.getInputStream().read())
                    }
                }
            }
        } finally {
            // Even a second Stop failure must not strand test-owned sockets
            // whose receive workers intentionally have no independent timeout.
            try { server.stop() }
            finally {
                upstreamSocket.forEach { socket ->
                    if (!socket.isClosed) {
                        try { socket.close() } catch (_: Exception) { /* synthetic close failure */ }
                    }
                }
            }
        }
    }
}

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
    private class FailingCloseUdpSocket : DatagramSocket() {
        val inReceive = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private val throwOnce = AtomicBoolean(true)
        override fun receive(packet: DatagramPacket) {
            inReceive.countDown()
            if (!closed.await(8, TimeUnit.SECONDS)) throw SocketException("synthetic receive stalled")
            throw SocketException("socket closed by Stop")
        }
        override fun close() {
            // Always release the real descriptor, even when the test throws.
            super.close()
            closed.countDown()
            if (throwOnce.compareAndSet(true, false)) {
                throw IllegalStateException("synthetic UDP close failure")
            }
        }
    }

    @Test fun throwingUdpCloseCannotPreventOtherClientCleanup() {
        val upstreamSocket = LinkedBlockingQueue<FailingCloseUdpSocket>()
        val upstream = object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket = error("TCP upstream not used")
            override fun resolve(host: String): Array<InetAddress> = arrayOf(InetAddress.getLoopbackAddress())
            override fun datagram(): DatagramSocket = error("not used")
            override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                assertEquals("127.0.0.1", host)
                assertEquals(9, port)
                val destination = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                val socket = FailingCloseUdpSocket()
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
                        val frame = Socks5.encodeDatagram(
                            Socks5.Address("127.0.0.1", 9), byteArrayOf(1))
                        sender.send(DatagramPacket(frame, frame.size,
                            InetSocketAddress(relay.host, relay.port)))
                        val remote = upstreamSocket.poll(3, TimeUnit.SECONDS)
                        assertNotNull("authenticated UDP upstream did not start", remote)
                        assertTrue("receive worker did not enter its loop",
                            remote!!.inReceive.await(3, TimeUnit.SECONDS))
                        server.stop() // Old code propagates synthetic close exception here.
                        assertEquals(0, server.boundPort)
                        assertTrue("failed close must still release real socket", remote.isClosed)
                        assertEquals("other active client must be closed during Stop",
                            -1, otherClient.getInputStream().read())
                        assertEquals("UDP control socket must also close", -1,
                            control.getInputStream().read())
                    }
                }
            }
        } finally {
            // A failed assertion/Stop must never leak a real descriptor or thread.
            server.stop()
            upstreamSocket.forEach { if (!it.isClosed) it.close() }
        }
    }
}

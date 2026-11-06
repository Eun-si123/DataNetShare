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
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real 128-slot global UDP permit pool when socket cleanup fails. */
class UdpPermitCloseFailureTest {
    /** A real FD is always closed, even though the simulated cleanup throws. */
    private class ThrowingCloseSocket(private val closedCount: AtomicInteger) : DatagramSocket() {
        override fun setSoTimeout(timeout: Int) {
            throw SocketException("synthetic pre-worker socket setup failure")
        }
        override fun close() {
            try { super.close() }
            finally {
                closedCount.incrementAndGet()
                throw IllegalStateException("synthetic cleanup callback failure")
            }
        }
    }

    @Test fun repeatedSocketOptionAndCloseFailuresDoNotExhaustUdpPermits() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(1024), 1024)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                } catch (_: IOException) { /* close() unblocks receive */ }
            }, "test-failed-close-udp-echo").apply { isDaemon = true; start() }
            val attempts = AtomicInteger()
            val closes = AtomicInteger()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = error("TCP unused")
                override fun resolve(host: String): Array<InetAddress> = error("UDP must resolve with binding")
                override fun datagram(): DatagramSocket = error("UDP must not allocate separately")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    assertEquals("echo.test", host)
                    assertEquals(echo.localPort, port)
                    if (attempts.incrementAndGet() <= 129) {
                        return Socks5Server.UdpConnection(
                            ThrowingCloseSocket(closes), InetSocketAddress("127.0.0.1", port))
                    }
                    val destination = InetSocketAddress("127.0.0.1", port)
                    return Socks5Server.UdpConnection(DatagramSocket().apply { connect(destination) }, destination)
                }
            }
            val proxy = Socks5Server("127.0.0.1", 0, "test-pass", upstream)
            proxy.start()
            try {
                Socket("127.0.0.1", proxy.boundPort).use { control ->
                    control.soTimeout = 5000
                    val input = DataInputStream(control.getInputStream())
                    Socks5.authenticateClient(input, control.getOutputStream(), "test-pass")
                    val relay = Socks5.request(input, control.getOutputStream(), 3,
                        Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { client ->
                        client.soTimeout = 5000
                        fun send(value: Byte) {
                            val frame = Socks5.encodeDatagram(
                                Socks5.Address("echo.test", echo.localPort), byteArrayOf(value))
                            client.send(DatagramPacket(frame, frame.size,
                                InetSocketAddress(relay.host, relay.port)))
                        }
                        repeat(129) { index ->
                            send(1)
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (attempts.get() < index + 1 && System.nanoTime() < deadline) Thread.sleep(2)
                            assertEquals("UDP permit leaked at failure #${index + 1}", index + 1, attempts.get())
                        }
                        val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (closes.get() < 129 && System.nanoTime() < closeDeadline) Thread.sleep(2)
                        assertEquals("all synthetic socket closures attempted", 129, closes.get())
                        send(42)
                        val response = DatagramPacket(ByteArray(1024), 1024)
                        client.receive(response)
                        assertArrayEquals(byteArrayOf(42),
                            Socks5.decodeDatagram(response.data.copyOf(response.length))!!.payload)
                        assertEquals("130th UDP upstream socket must still get a permit", 130, attempts.get())
                    }
                }
            } finally { proxy.stop() }
        }
    }

    /** The receive worker closes a real FD, then its synthetic cleanup throws. */
    private class FailingReceiveSocket(private val closedCount: AtomicInteger) : DatagramSocket() {
        val sent = CountDownLatch(1)
        private val releaseReceive = CountDownLatch(1)

        override fun send(packet: DatagramPacket) {
            super.send(packet)
            sent.countDown()
        }

        override fun receive(packet: DatagramPacket) {
            if (!releaseReceive.await(3, TimeUnit.SECONDS)) {
                throw SocketException("synthetic receive gate timed out")
            }
            throw SocketException("synthetic upstream network disconnected")
        }

        fun failReceive() = releaseReceive.countDown()

        override fun close() {
            try { super.close() }
            finally {
                closedCount.incrementAndGet()
                throw IllegalStateException("synthetic receive-worker cleanup failure")
            }
        }
    }

    @Test fun receiveWorkerCloseFailureMustNotExhaustUdpPermits() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(1024), 1024)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                } catch (_: IOException) { /* echo close */ }
            }, "test-worker-close-udp-echo").apply { isDaemon = true; start() }

            val opened = AtomicInteger()
            val closed = AtomicInteger()
            val failing = LinkedBlockingQueue<FailingReceiveSocket>()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = error("TCP unused")
                override fun resolve(host: String): Array<InetAddress> = error("UDP must resolve with binding")
                override fun datagram(): DatagramSocket = error("UDP must not allocate separately")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    assertEquals("echo.test", host)
                    assertEquals(echo.localPort, port)
                    val destination = InetSocketAddress("127.0.0.1", port)
                    if (opened.incrementAndGet() <= 129) {
                        val socket = FailingReceiveSocket(closed)
                        socket.connect(destination)
                        failing.add(socket)
                        return Socks5Server.UdpConnection(socket, destination)
                    }
                    return Socks5Server.UdpConnection(
                        DatagramSocket().apply { connect(destination) }, destination)
                }
            }
            val proxy = Socks5Server("127.0.0.1", 0, "test-pass", upstream)
            proxy.start()
            try {
                Socket("127.0.0.1", proxy.boundPort).use { control ->
                    control.soTimeout = 4000
                    val input = DataInputStream(control.getInputStream())
                    Socks5.authenticateClient(input, control.getOutputStream(), "test-pass")
                    val relay = Socks5.request(input, control.getOutputStream(), 3,
                        Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { client ->
                        client.soTimeout = 5000
                        fun send(value: Byte) {
                            val frame = Socks5.encodeDatagram(
                                Socks5.Address("echo.test", echo.localPort), byteArrayOf(value))
                            client.send(DatagramPacket(frame, frame.size,
                                InetSocketAddress(relay.host, relay.port)))
                        }
                        repeat(129) { index ->
                            send(1)
                            val socket = failing.poll(2, TimeUnit.SECONDS)
                            assertNotNull("permit leaked at receive cleanup #${index + 1}", socket)
                            assertTrue("sender did not use allocated UDP socket",
                                socket!!.sent.await(2, TimeUnit.SECONDS))
                            socket.failReceive()
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (closed.get() < index + 1 && System.nanoTime() < deadline) Thread.sleep(2)
                            assertEquals("worker must close its socket", index + 1, closed.get())
                        }
                        send(42)
                        val response = DatagramPacket(ByteArray(1024), 1024)
                        client.receive(response)
                        assertArrayEquals(byteArrayOf(42),
                            Socks5.decodeDatagram(response.data.copyOf(response.length))!!.payload)
                        assertEquals("worker teardown must release all 129 permits", 130, opened.get())
                    }
                }
            } finally { proxy.stop() }
        }
    }
}

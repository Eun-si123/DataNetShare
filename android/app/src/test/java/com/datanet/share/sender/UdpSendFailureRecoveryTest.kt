package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UdpSendFailureRecoveryTest {
    @Test fun failedSendRetiresSocketAndNextDatagramOpensFreshUpstream() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                } catch (_: IOException) { }
            }, "test-udp-recovery-echo").apply { isDaemon = true; start() }

            val failedSend = CountDownLatch(1)
            val opened = AtomicInteger()
            var broken: DatagramSocket? = null
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = throw IOException("unused")
                override fun resolve(host: String) = throw AssertionError("no split UDP resolution")
                override fun datagram(): DatagramSocket = throw AssertionError("no split UDP socket")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    assertEquals("echo.test", host)
                    assertEquals(echo.localPort, port)
                    val destination = InetSocketAddress("127.0.0.1", port)
                    val socket = if (opened.incrementAndGet() == 1) {
                        object : DatagramSocket() {
                            override fun send(packet: DatagramPacket) {
                                failedSend.countDown()
                                throw SocketException("simulated network lost on UDP send")
                            }
                        }.also { broken = it }
                    } else DatagramSocket()
                    socket.connect(destination)
                    return Socks5Server.UdpConnection(socket, destination)
                }
            }
            val proxy = Socks5Server("127.0.0.1", 0, "test-pass", upstream)
            proxy.start()
            try {
                Socket("127.0.0.1", proxy.boundPort).use { control ->
                    control.soTimeout = 3000
                    val input = DataInputStream(control.getInputStream())
                    Socks5.authenticateClient(input, control.getOutputStream(), "test-pass")
                    val relay = Socks5.request(input, control.getOutputStream(), 3, Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { client ->
                        client.soTimeout = 3000
                        fun send(value: Int) {
                            val frame = Socks5.encodeDatagram(Socks5.Address("echo.test", echo.localPort), byteArrayOf(value.toByte()))
                            client.send(DatagramPacket(frame, frame.size, InetSocketAddress(relay.host, relay.port)))
                        }
                        send(1)
                        assertTrue("first upstream send did not fail", failedSend.await(3, TimeUnit.SECONDS))
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (broken?.isClosed != true && System.nanoTime() < deadline) Thread.sleep(10)
                        assertTrue("failed UDP socket must be retired before a later packet", broken?.isClosed == true)
                        send(2) // new datagram, not retransmission of the failed first packet
                        val reply = DatagramPacket(ByteArray(256), 256)
                        client.receive(reply)
                        val decoded = Socks5.decodeDatagram(reply.data.copyOf(reply.length))!!
                        assertArrayEquals(byteArrayOf(2), decoded.payload)
                        assertEquals("only a later datagram reopens upstream", 2, opened.get())
                    }
                }
            } finally { proxy.stop() }
        }
    }
}

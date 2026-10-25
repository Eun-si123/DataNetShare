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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class UdpAssociationCleanupTest {
    @Test fun closingOneControlReleasesOnlyItsUdpSocketAndStopReleasesTheOther() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                } catch (_: IOException) { }
            }, "test-udp-cleanup-echo").apply { isDaemon = true; start() }

            val opened = ConcurrentLinkedQueue<DatagramSocket>()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = throw IOException("unused")
                override fun resolve(host: String) = throw AssertionError("no split UDP resolution")
                override fun datagram(): DatagramSocket = throw AssertionError("no split UDP binding")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    assertEquals("echo.test", host)
                    assertEquals(echo.localPort, port)
                    val destination = InetSocketAddress("127.0.0.1", port)
                    val socket = DatagramSocket()
                    socket.connect(destination)
                    opened.add(socket)
                    return Socks5Server.UdpConnection(socket, destination)
                }
            }
            val proxy = Socks5Server("127.0.0.1", 0, "test-pass", upstream)
            proxy.start()
            try {
                fun control(): Pair<Socket, Socks5.Address> {
                    val socket = Socket("127.0.0.1", proxy.boundPort)
                    socket.soTimeout = 3000
                    val input = DataInputStream(socket.getInputStream())
                    Socks5.authenticateClient(input, socket.getOutputStream(), "test-pass")
                    val relay = Socks5.request(input, socket.getOutputStream(), 3,
                        Socks5.Address("0.0.0.0", 0))
                    return socket to relay
                }
                val (first, relay1) = control()
                val (second, relay2) = control()
                try {
                    DatagramSocket().use { udp1 ->
                        DatagramSocket().use { udp2 ->
                            udp1.soTimeout = 3000
                            udp2.soTimeout = 3000
                            fun roundTrip(client: DatagramSocket, relay: Socks5.Address, value: Byte) {
                                val frame = Socks5.encodeDatagram(
                                    Socks5.Address("echo.test", echo.localPort), byteArrayOf(value))
                                client.send(DatagramPacket(frame, frame.size,
                                    InetSocketAddress(relay.host, relay.port)))
                                val response = DatagramPacket(ByteArray(256), 256)
                                client.receive(response)
                                assertArrayEquals(byteArrayOf(value),
                                    Socks5.decodeDatagram(response.data.copyOf(response.length))!!.payload)
                            }
                            roundTrip(udp1, relay1, 1)
                            roundTrip(udp2, relay2, 2)
                            assertEquals("one upstream socket per association", 2, opened.size)
                            val firstUpstream = opened.elementAt(0)
                            val secondUpstream = opened.elementAt(1)
                            first.close() // TCP control EOF must close only this association.
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                            while (!firstUpstream.isClosed && System.nanoTime() < deadline) {
                                Thread.sleep(10)
                            }
                            assertTrue("closed control must retire its UDP socket", firstUpstream.isClosed)
                            assertFalse("other authenticated association must survive", secondUpstream.isClosed)
                            roundTrip(udp2, relay2, 3)
                            proxy.stop() // Global Stop must retire the remaining UDP socket.
                            assertTrue("Stop must close remaining UDP socket", secondUpstream.isClosed)
                        }
                    }
                } finally {
                    first.close()
                    second.close()
                }
            } finally {
                proxy.stop()
                opened.forEach { it.close() }
            }
        }
    }
}

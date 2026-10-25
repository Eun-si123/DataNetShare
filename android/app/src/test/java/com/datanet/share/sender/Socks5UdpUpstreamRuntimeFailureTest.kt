package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Socks5UdpUpstreamRuntimeFailureTest {
    @Test fun permissionFailureDoesNotPermanentlyKillUdpAssociation() =
        verifyRecovery { SecurityException("synthetic route permission denial") }

    @Test fun invalidPlatformRouteDoesNotPermanentlyKillUdpAssociation() =
        verifyRecovery { IllegalArgumentException("synthetic invalid route") }

    private fun verifyRecovery(failure: () -> RuntimeException) {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                } catch (_: java.io.IOException) { }
            }, "test-udp-echo").apply { isDaemon = true; start() }

            val attempts = AtomicInteger()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = error("unused")
                override fun resolve(host: String): Array<InetAddress> = arrayOf(InetAddress.getByName("127.0.0.1"))
                override fun datagram(): DatagramSocket = DatagramSocket()
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    if (attempts.incrementAndGet() == 1) throw failure()
                    val destination = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
                    return Socks5Server.UdpConnection(DatagramSocket().apply { connect(destination) }, destination)
                }
            }
            val proxy = Socks5Server("127.0.0.1", 0, "test-password", upstream)
            proxy.start()
            try {
                Socket("127.0.0.1", proxy.boundPort).use { client ->
                    client.soTimeout = 3000
                    Socks5.authenticateClient(DataInputStream(client.getInputStream()),
                        client.getOutputStream(), "test-password")
                    val relay = Socks5.request(DataInputStream(client.getInputStream()),
                        client.getOutputStream(), 3, Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { udp ->
                        udp.soTimeout = 1200
                        val target = InetSocketAddress(relay.host, relay.port)
                        fun send(value: Byte) {
                            val frame = Socks5.encodeDatagram(
                                Socks5.Address("127.0.0.1", echo.localPort), byteArrayOf(value))
                            udp.send(DatagramPacket(frame, frame.size, target))
                        }
                        send(1)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (attempts.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                        assertEquals("first datagram should trigger upstream setup", 1, attempts.get())
                        send(2)
                        val response = DatagramPacket(ByteArray(4096), 4096)
                        udp.receive(response)
                        val decoded = Socks5.decodeDatagram(response.data.copyOf(response.length))
                        assertArrayEquals(byteArrayOf(2), decoded?.payload)
                        assertEquals(2, attempts.get())
                    }
                }
            } finally {
                proxy.stop()
            }
        }
    }
}

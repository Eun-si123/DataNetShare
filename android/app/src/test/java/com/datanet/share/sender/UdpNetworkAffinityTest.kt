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
import java.util.concurrent.atomic.AtomicInteger

class UdpNetworkAffinityTest {
    @Test fun udpUsesOneAtomicResolutionAndBindingPerDestination() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            val received = AtomicInteger()
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        echo.receive(packet)
                        received.incrementAndGet()
                        echo.send(packet)
                    }
                } catch (_: IOException) { }
            }, "test-udp-affinity-echo").apply { isDaemon = true; start() }
            val opened = AtomicInteger()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = throw IOException("unused")
                override fun resolve(host: String): Array<InetAddress> =
                    throw AssertionError("UDP must not resolve separately from socket binding")
                override fun datagram(): DatagramSocket =
                    throw AssertionError("UDP must not bind a separate socket")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    opened.incrementAndGet()
                    if (host == "setup-fails.test") throw IOException("simulated pre-send binding failure")
                    assertEquals("echo.test", host)
                    assertEquals(echo.localPort, port)
                    val destination = InetSocketAddress("127.0.0.1", port)
                    return DatagramSocket().let { socket ->
                        socket.connect(destination)
                        Socks5Server.UdpConnection(socket, destination)
                    }
                }
            }
            val proxy = Socks5Server("127.0.0.1", 0, "test-pass", upstream)
            proxy.start()
            try {
                Socket("127.0.0.1", proxy.boundPort).use { control ->
                    control.soTimeout = 3000
                    Socks5.authenticateClient(DataInputStream(control.getInputStream()),
                        control.getOutputStream(), "test-pass")
                    val relay = Socks5.request(DataInputStream(control.getInputStream()),
                        control.getOutputStream(), 3, Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { client ->
                        client.soTimeout = 3000
                        fun send(host: String, value: Int) {
                            val frame = Socks5.encodeDatagram(Socks5.Address(host, echo.localPort),
                                byteArrayOf(value.toByte()))
                            client.send(DatagramPacket(frame, frame.size,
                                InetSocketAddress(relay.host, relay.port)))
                        }
                        send("setup-fails.test", 99)
                        // The failed pre-send setup must not poison this UDP association.
                        send("echo.test", 1)
                        fun receive(expected: Int) {
                            val packet = DatagramPacket(ByteArray(256), 256)
                            client.receive(packet)
                            val decoded = Socks5.decodeDatagram(packet.data.copyOf(packet.length))!!
                            assertEquals("127.0.0.1", decoded.address.host)
                            assertEquals(echo.localPort, decoded.address.port)
                            assertArrayEquals(byteArrayOf(expected.toByte()), decoded.payload)
                        }
                        receive(1)
                        send("echo.test", 2)
                        receive(2)
                    }
                }
                assertEquals("one failed setup, one successful socket", 2, opened.get())
                assertEquals("each payload is sent exactly once", 2, received.get())
            } finally { proxy.stop() }
        }
    }
}

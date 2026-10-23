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
            val echoAttempts = AtomicInteger()
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
                    // A failed setup for the *same* destination must not be cached.
                    if (echoAttempts.incrementAndGet() == 1) throw IOException("simulated first echo setup failure")
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
                        // Both a different destination and the same destination must be
                        // retryable after a pre-send failure, without duplicating payloads.
                        send("echo.test", 98) // first setup fails; this payload is never sent
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
                assertEquals("two failed setups, one successful socket", 3, opened.get())
                assertEquals("each payload is sent exactly once", 2, received.get())
            } finally { proxy.stop() }
        }
    }

    @Test fun repeatedPreSendFailuresReleaseGlobalUdpCapacity() {
        // The sender has 128 global upstream UDP permits. A failed setup must
        // release its permit even if the same authenticated association retries.
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                } catch (_: IOException) { }
            }, "test-udp-permit-echo").apply { isDaemon = true; start() }

            val attempts = AtomicInteger()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = throw IOException("unused")
                override fun resolve(host: String): Array<InetAddress> =
                    throw AssertionError("UDP must not resolve separately")
                override fun datagram(): DatagramSocket =
                    throw AssertionError("UDP must not bind separately")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    assertEquals("echo.test", host)
                    assertEquals(echo.localPort, port)
                    if (attempts.incrementAndGet() <= 130) {
                        throw IOException("simulated DNS/bind setup failure before send")
                    }
                    val destination = InetSocketAddress("127.0.0.1", port)
                    val socket = DatagramSocket()
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
                    val relay = Socks5.request(input, control.getOutputStream(), 3,
                        Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { client ->
                        client.soTimeout = 3000
                        fun send(value: Byte) {
                            val frame = Socks5.encodeDatagram(
                                Socks5.Address("echo.test", echo.localPort), byteArrayOf(value))
                            client.send(DatagramPacket(frame, frame.size,
                                InetSocketAddress(relay.host, relay.port)))
                        }
                        repeat(130) { index ->
                            send(1)
                            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                            while (attempts.get() < index + 1 && System.nanoTime() < deadline) {
                                Thread.sleep(2)
                            }
                            assertEquals("pre-send failure must remain retryable", index + 1, attempts.get())
                        }
                        send(42)
                        val reply = DatagramPacket(ByteArray(256), 256)
                        client.receive(reply)
                        assertArrayEquals(byteArrayOf(42),
                            Socks5.decodeDatagram(reply.data.copyOf(reply.length))!!.payload)
                        assertEquals("130 failed setups must not exhaust 128 permits", 131, attempts.get())
                    }
                }
            } finally { proxy.stop() }
        }
    }

}

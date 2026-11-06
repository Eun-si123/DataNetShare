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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UdpRepeatedLifecycleCapacityTest {
    @Test fun repeatedStopAndRestartReclaimsUdpWorkersAndGlobalPermits() {
        // 5 * 32 unique UDP destinations exceed the 128 global socket limit
        // if prior sessions leak permits. No Android network or VPN is involved.
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            val droppedFirstEcho = AtomicBoolean(false)
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        echo.receive(packet)
                        // A UDP resource-capacity test must tolerate datagram loss,
                        // not assume reliable delivery. Force one loss to prove
                        // the bounded retry path is exercised on every CI OS.
                        if (packet.length == 2 && packet.data[packet.offset] == 0.toByte() &&
                            packet.data[packet.offset + 1] == 0.toByte() &&
                            droppedFirstEcho.compareAndSet(false, true)) continue
                        echo.send(packet)
                    }
                } catch (_: IOException) { }
            }, "test-repeated-lifecycle-echo").apply { isDaemon = true; start() }

            val opened = AtomicInteger()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = throw IOException("unused")
                override fun resolve(host: String): Array<InetAddress> =
                    throw AssertionError("UDP setup must be atomic")
                override fun datagram(): DatagramSocket =
                    throw AssertionError("UDP setup must be atomic")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    assertTrue("expected a distinct test destination", host.startsWith("echo-"))
                    assertEquals(echo.localPort, port)
                    val destination = InetSocketAddress("127.0.0.1", echo.localPort)
                    val socket = DatagramSocket()
                    socket.connect(destination)
                    opened.incrementAndGet()
                    return Socks5Server.UdpConnection(socket, destination)
                }
            }
            val proxy = Socks5Server("127.0.0.1", 0, "test-pass", upstream)
            fun udpWorkers(): Int = Thread.getAllStackTraces().keys.count {
                it.isAlive && it.name == "socks-udp-upstream"
            }
            val baselineWorkers = udpWorkers()
            try {
                repeat(5) { cycle ->
                    proxy.start()
                    try {
                        Socket("127.0.0.1", proxy.boundPort).use { control ->
                            control.soTimeout = 5000
                            val input = DataInputStream(control.getInputStream())
                            Socks5.authenticateClient(input, control.getOutputStream(), "test-pass")
                            val relay = Socks5.request(input, control.getOutputStream(), 3,
                                Socks5.Address("0.0.0.0", 0))
                            DatagramSocket().use { client ->
                                repeat(32) { destinationIndex ->
                                    val payload = byteArrayOf(cycle.toByte(), destinationIndex.toByte())
                                    val frame = Socks5.encodeDatagram(
                                        Socks5.Address("echo-${destinationIndex}.test", echo.localPort),
                                        payload
                                    )
                                    var matched = false
                                    // UDP can drop even localhost datagrams under runner load.
                                    // This test checks permit recovery, not lossless delivery;
                                    // retry only this test packet, with a hard per-attempt bound.
                                    repeat(3) {
                                        if (!matched) {
                                            client.send(DatagramPacket(frame, frame.size,
                                                InetSocketAddress(relay.host, relay.port)))
                                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                                            while (!matched && System.nanoTime() < deadline) {
                                                val remaining = deadline - System.nanoTime()
                                                client.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining)
                                                    .coerceAtLeast(1).toInt()
                                                val reply = DatagramPacket(ByteArray(256), 256)
                                                try { client.receive(reply) }
                                                catch (_: SocketTimeoutException) { break }
                                                val data = Socks5.decodeDatagram(reply.data.copyOf(reply.length))
                                                assertNotNull("invalid UDP response", data)
                                                val received = data!!.payload
                                                if (received.contentEquals(payload)) matched = true
                                                else {
                                                    // A previous destination's delayed retry may
                                                    // arrive later, but unrelated payloads are wrong.
                                                    assertTrue("unexpected UDP payload", received.size == 2 &&
                                                        received[0] == cycle.toByte() &&
                                                        (received[1].toInt() and 0xff) < destinationIndex)
                                                }
                                            }
                                        }
                                    }
                                    assertTrue("UDP echo missing after bounded retries: cycle=$cycle, index=$destinationIndex",
                                        matched)
                                }
                            }
                        }
                    } finally {
                        proxy.stop()
                    }
                    // Stop closes every destination socket; each receive worker must
                    // finish its finally block and return its global permit.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (udpWorkers() > baselineWorkers && System.nanoTime() < deadline) {
                        Thread.sleep(10)
                    }
                    assertEquals("UDP workers leaked after stop cycle ${cycle + 1}",
                        baselineWorkers, udpWorkers())
                }
                assertTrue("test must exercise one intentional dropped datagram", droppedFirstEcho.get())
                assertEquals("every new destination should have obtained a fresh socket",
                    160, opened.get())
            } finally {
                proxy.stop()
            }
        }
    }
}

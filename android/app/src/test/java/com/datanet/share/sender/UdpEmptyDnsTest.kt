package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UdpEmptyDnsTest {
    @Test fun emptyDnsAnswerDoesNotTerminateUdpWorker() {
        val resolveCalls = AtomicInteger()
        val firstResolution = CountDownLatch(1)
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                } catch (_: IOException) { }
            }, "test-udp-echo").apply { isDaemon = true; start() }

            val auth = "local-test-only"
            val proxy = Socks5Server("127.0.0.1", 0, auth, object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket =
                    throw IOException("TCP not used in UDP test")
                override fun resolve(host: String): Array<InetAddress> {
                    if (resolveCalls.getAndIncrement() == 0) {
                        firstResolution.countDown()
                        return emptyArray()
                    }
                    return arrayOf(InetAddress.getByName("127.0.0.1"))
                }
                override fun datagram() = DatagramSocket()
            })
            proxy.start()
            try {
                Socket("127.0.0.1", proxy.boundPort).use { control ->
                    control.soTimeout = 3000
                    val input = DataInputStream(control.getInputStream())
                    val output = control.getOutputStream()
                    Socks5.authenticateClient(input, output, auth)
                    val relay = Socks5.request(input, output, 3, Socks5.Address("0.0.0.0", 0))
                    DatagramSocket().use { udp ->
                        udp.soTimeout = 3000
                        val target = InetSocketAddress(relay.host, relay.port)
                        fun send(payload: ByteArray) {
                            val frame = Socks5.encodeDatagram(
                                Socks5.Address("127.0.0.1", echo.localPort), payload)
                            udp.send(DatagramPacket(frame, frame.size, target))
                        }
                        send(byteArrayOf(1))
                        assertTrue("first DNS resolution was not observed",
                            firstResolution.await(3, TimeUnit.SECONDS))
                        send(byteArrayOf(2, 3))
                        val reply = DatagramPacket(ByteArray(4096), 4096)
                        udp.receive(reply)
                        assertArrayEquals(byteArrayOf(2, 3),
                            Socks5.decodeDatagram(reply.data.copyOf(reply.length))!!.payload)
                        assertEquals(2, resolveCalls.get())
                    }
                }
            } finally { proxy.stop() }
        }
    }
}

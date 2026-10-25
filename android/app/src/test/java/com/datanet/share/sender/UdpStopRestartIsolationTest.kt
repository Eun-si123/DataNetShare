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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UdpStopRestartIsolationTest {
    @Test fun delayedOldUdpSetupCannotEscapeStopAndRestart() {
        // Hold the first DNS/socket setup across Stop and Start. A stale worker
        // must close its newly returned socket, release its permit, and never
        // forward its old packet into the restarted session.
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { echo ->
            val received = AtomicInteger()
            Thread({
                try {
                    while (!echo.isClosed) {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        echo.receive(packet)
                        received.incrementAndGet()
                        echo.send(packet)
                    }
                } catch (_: IOException) { }
            }, "test-udp-restart-echo").apply { isDaemon = true; start() }

            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val opened = AtomicInteger()
            val staleSocket = AtomicReference<DatagramSocket>()
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = throw IOException("unused")
                override fun resolve(host: String) = throw AssertionError("no split UDP resolution")
                override fun datagram(): DatagramSocket = throw AssertionError("no split UDP socket")
                override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                    assertEquals("echo.test", host)
                    assertEquals(echo.localPort, port)
                    val attempt = opened.incrementAndGet()
                    if (attempt == 1) {
                        entered.countDown()
                        check(resume.await(5, TimeUnit.SECONDS)) { "stale setup was not resumed" }
                    }
                    val destination = InetSocketAddress("127.0.0.1", port)
                    val socket = DatagramSocket()
                    socket.connect(destination)
                    if (attempt == 1) staleSocket.set(socket)
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
                fun send(client: DatagramSocket, relay: Socks5.Address, value: Byte) {
                    val frame = Socks5.encodeDatagram(
                        Socks5.Address("echo.test", echo.localPort), byteArrayOf(value))
                    client.send(DatagramPacket(frame, frame.size,
                        InetSocketAddress(relay.host, relay.port)))
                }

                val (oldControl, oldRelay) = control()
                try {
                    DatagramSocket().use { oldUdp ->
                        send(oldUdp, oldRelay, 1)
                        assertTrue("old UDP setup did not begin", entered.await(3, TimeUnit.SECONDS))
                        proxy.stop()
                        proxy.start()
                        resume.countDown()

                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (staleSocket.get()?.isClosed != true && System.nanoTime() < deadline) {
                            Thread.sleep(10)
                        }
                        assertTrue("late old-session socket must be closed",
                            staleSocket.get()?.isClosed == true)
                    }
                } finally {
                    oldControl.close()
                }

                val (newControl, newRelay) = control()
                try {
                    DatagramSocket().use { newUdp ->
                        newUdp.soTimeout = 3000
                        send(newUdp, newRelay, 2)
                        val reply = DatagramPacket(ByteArray(256), 256)
                        newUdp.receive(reply)
                        val decoded = Socks5.decodeDatagram(reply.data.copyOf(reply.length))!!
                        assertArrayEquals(byteArrayOf(2), decoded.payload)
                        assertEquals("one stale and one new upstream setup", 2, opened.get())
                        assertEquals("old datagram must never reach the destination", 1, received.get())
                    }
                } finally {
                    newControl.close()
                }
            } finally {
                resume.countDown()
                proxy.stop()
                staleSocket.get()?.close()
            }
        }
    }
}

package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Observe the actual UDP ASSOCIATE request written by UdpFlow, not a mock policy. */
class UdpFlowAdvertisedSourcePortTest {
    @Test fun receiverAdvertisesItsProtectedPreboundUdpPort() {
        val password = "synthetic-receiver-password"
        val advertised = AtomicInteger(0)
        val udpSocket = AtomicReference<DatagramSocket>()
        val senderError = AtomicReference<Throwable?>()
        val messages = LinkedBlockingQueue<String>()
        val receivedRequest = CountDownLatch(1)
        val receiverConnected = CountDownLatch(1)
        val finishSender = CountDownLatch(1)
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { relay ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 4_000
                val sender = Thread({
                    try {
                        listener.accept().use { peer ->
                            peer.soTimeout = 4_000
                            val input = DataInputStream(peer.getInputStream())
                            val output = peer.getOutputStream()
                            Socks5.authenticateServer(input, output, password)
                            assertEquals(5, input.readUnsignedByte())
                            assertEquals(3, input.readUnsignedByte())
                            assertEquals(0, input.readUnsignedByte())
                            val address = Socks5.readAddress(input)
                            assertEquals("0.0.0.0", address.host)
                            advertised.set(address.port)
                            receivedRequest.countDown()
                            Socks5.reply(output, 0, Socks5.Address("127.0.0.1", relay.localPort))
                            finishSender.await(6, TimeUnit.SECONDS)
                        }
                    } catch (error: Throwable) {
                        senderError.set(error)
                        receivedRequest.countDown()
                    }
                }, "test-udp-advertised-port-sender").apply { isDaemon = true; start() }
                val flow = UdpFlow(
                    FlowKey(0x0a000001, 19032, 0x7f000001, relay.localPort),
                    "127.0.0.1", listener.localPort, password,
                    { true },
                    { socket ->
                        // Production protects/binds the Network before local bind.
                        assertFalse("protect must precede local bind", socket.isBound)
                        assertFalse("protect must precede connect", socket.isConnected)
                        udpSocket.set(socket)
                        true
                    },
                    {}, {}, {}, {},
                    onError = { messages.add(it) },
                    onConnected = { receiverConnected.countDown() }
                )
                val worker = Thread({ flow.start() }, "test-udp-advertised-port-receiver")
                    .apply { isDaemon = true; start() }
                try {
                    assertTrue("sender did not receive SOCKS request",
                        receivedRequest.await(4, TimeUnit.SECONDS))
                    senderError.get()?.let { throw AssertionError("synthetic sender failed", it) }
                    assertTrue("receiver did not establish UDP association",
                        receiverConnected.await(4, TimeUnit.SECONDS))
                    val socket = udpSocket.get()
                    assertNotNull("protected UDP socket was not published", socket)
                    assertTrue("UDP socket must be bound before the request", socket.isBound)
                    assertTrue("UDP source port must be explicit", advertised.get() in 1..65535)
                    assertEquals("advertised port must match actual UDP socket", socket.localPort,
                        advertised.get())
                    assertFalse(socket.isClosed)
                    assertTrue("no endpoint or password may appear in diagnostics", messages.isEmpty())
                } finally {
                    flow.close()
                    finishSender.countDown()
                    worker.join(3_000)
                    sender.join(3_000)
                    assertFalse("receiver worker leaked after Stop", worker.isAlive)
                    assertFalse("synthetic sender leaked", sender.isAlive)
                }
            }
        }
    }
}

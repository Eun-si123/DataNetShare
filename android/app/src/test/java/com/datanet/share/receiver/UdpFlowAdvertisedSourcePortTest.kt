package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
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
        val tunOutput = LinkedBlockingQueue<ByteArray>()
        val receivedRequest = CountDownLatch(1)
        val receiverConnected = CountDownLatch(1)
        val finishSender = CountDownLatch(1)
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { relay ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 8_000
                val sender = Thread({
                    try {
                        listener.accept().use { peer ->
                            peer.soTimeout = 8_000
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
                            // Simulate the pre-connect receive queue. Both
                            // datagrams arrive from the same IP as the SOCKS5
                            // relay but from a different UDP source port.
                            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { attacker ->
                                val target = InetSocketAddress("127.0.0.1", address.port)
                                attacker.send(DatagramPacket(ByteArray(0), 0, target))
                                val spoofed = Socks5.encodeDatagram(
                                    Socks5.Address("127.0.0.1", relay.localPort), byteArrayOf(99))
                                attacker.send(DatagramPacket(spoofed, spoofed.size, target))
                            }
                            Socks5.reply(output, 0, Socks5.Address("127.0.0.1", relay.localPort))
                            finishSender.await(14, TimeUnit.SECONDS)
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
                    { tunOutput.add(it) }, {}, {}, {},
                    onError = { messages.add(it) },
                    onConnected = { receiverConnected.countDown() }
                )
                val worker = Thread({ flow.start() }, "test-udp-advertised-port-receiver")
                    .apply { isDaemon = true; start() }
                try {
                    assertTrue("sender did not receive SOCKS request",
                        receivedRequest.await(8, TimeUnit.SECONDS))
                    senderError.get()?.let { throw AssertionError("synthetic sender failed", it) }
                    assertTrue("receiver did not establish UDP association",
                        receiverConnected.await(8, TimeUnit.SECONDS))
                    val socket = udpSocket.get()
                    assertNotNull("protected UDP socket was not published", socket)
                    assertTrue("UDP socket must be bound before the request", socket.isBound)
                    assertTrue("UDP source port must be explicit", advertised.get() in 1..65535)
                    assertEquals("advertised port must match actual UDP socket", socket.localPort,
                        advertised.get())
                    assertFalse(socket.isClosed)
                    assertFalse("unconnected socket must reveal true sender addresses", socket.isConnected)
                    assertNull("pre-connect same-IP wrong-port datagram reached TUN",
                        tunOutput.poll(300, TimeUnit.MILLISECONDS))
                    // The real relay port is still accepted, proving source
                    // validation does not break valid DNS/QUIC response traffic.
                    val valid = Socks5.encodeDatagram(
                        Socks5.Address("127.0.0.1", relay.localPort), byteArrayOf(7))
                    relay.send(DatagramPacket(valid, valid.size,
                        InetSocketAddress("127.0.0.1", socket.localPort)))
                    val accepted = tunOutput.poll(5, TimeUnit.SECONDS)
                    assertNotNull("genuine relay response must reach TUN", accepted)
                    assertArrayEquals(byteArrayOf(7), Ipv4Packet.parse(accepted!!)!!.payload)
                    assertTrue("no endpoint or password may appear in diagnostics", messages.isEmpty())
                } finally {
                    flow.close()
                    finishSender.countDown()
                    worker.join(4_000)
                    sender.join(4_000)
                    assertFalse("receiver worker leaked after Stop", worker.isAlive)
                    assertFalse("synthetic sender leaked", sender.isAlive)
                }
            }
        }
    }
}

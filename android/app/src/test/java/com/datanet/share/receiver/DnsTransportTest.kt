package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** DNS wire traffic over the real bridge, not Android's DNS resolver or an app. */
class DnsTransportTest {
    @Test fun aAndAaaaAnswersBothTravelOverIpv4Udp() = DnsFixture().use { dns ->
        val packets = LinkedBlockingQueue<Ipv4Packet>()
        val flow = dns.udpFlow { packets.add(Ipv4Packet.parse(it)!!) }
        try {
            // Queue both first queries before SOCKS UDP ASSOCIATE completes.
            val a = query(1)
            val aaaa = query(28)
            flow.send(a); flow.send(aaaa)
            Thread({ flow.start() }, "test-dns-udp-flow").apply { isDaemon = true; start() }
            val replies = listOf(packets.poll(5, TimeUnit.SECONDS)!!,
                packets.poll(5, TimeUnit.SECONDS)!!).associateBy { it.payload[1].toInt() and 255 }
            assertArrayEquals(answer(a, false), replies[1]!!.payload)
            assertArrayEquals(answer(aaaa, false), replies[28]!!.payload)
            assertTrue(replies.values.all { it.protocol == 17 && it.key.source == 0x7f000001 })
        } finally { flow.close() }
    }

    @Test fun truncatedUdpDnsCanBeRetriedOverTcpWithAnAnswerLargerThanMtu() = DnsFixture().use { dns ->
        val udpPackets = LinkedBlockingQueue<Ipv4Packet>()
        val udp = dns.udpFlow { udpPackets.add(Ipv4Packet.parse(it)!!) }
        val question = query(16)
        try {
            udp.send(question)
            Thread({ udp.start() }, "test-dns-truncated-flow").apply { isDaemon = true; start() }
            val truncated = udpPackets.poll(5, TimeUnit.SECONDS)!!.payload
            assertArrayEquals(answer(question, false), truncated)
            assertEquals("fixture must set DNS TC flag", 2, truncated[2].toInt() and 2)
        } finally { udp.close() }

        // Emulate a resolver explicitly retrying a TC response over TCP.
        // The bridge does not invent TC responses or automatically retry DNS.
        val expected = frame(answer(question, true))
        assertTrue(expected.size > 1500)
        val request = frame(question)
        val packets = LinkedBlockingQueue<Ipv4Packet>()
        val flow = TcpFlow(dns.key, 1000, "127.0.0.1", dns.proxy.proxy.boundPort,
            dns.proxy.password, { true }, { packets.add(Ipv4Packet.parse(it)!!) },
            {}, {}, {}, initialSequence = 5000)
        try {
            flow.start()
            assertEquals(0x12, packets.poll(2, TimeUnit.SECONDS)!!.flags)
            flow.receive(Ipv4Packet(dns.key, 6, 1001, 5001, 0x18, 8192, request))
            Thread({ flow.openUpstream() }, "test-dns-tcp-flow").apply { isDaemon = true; start() }
            val received = ByteArrayOutputStream()
            var next = 5001
            var fin = false
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!fin && System.nanoTime() < deadline) {
                val packet = packets.poll(100, TimeUnit.MILLISECONDS) ?: continue
                assertEquals("DNS TCP reply must not reset", 0, packet.flags and 4)
                if (packet.payload.isEmpty() && packet.flags and 1 == 0) continue
                assertEquals(next, packet.sequence)
                assertTrue("each IPv4 TCP packet fits VPN MTU", packet.payload.size + 40 <= 1500)
                received.write(packet.payload); next += packet.payload.size
                if (packet.flags and 1 != 0) { next++; fin = true }
                flow.receive(Ipv4Packet(dns.key, 6, 1001 + request.size, next, 0x10, 8192, ByteArray(0)))
            }
            assertTrue("TCP DNS response must finish", fin)
            assertArrayEquals(expected, received.toByteArray())
            assertArrayEquals(question, dns.tcpQueries.poll(2, TimeUnit.SECONDS))
        } finally { flow.close() }
    }

    private class DnsFixture : AutoCloseable {
        val proxy = ProxyFixture()
        // The SOCKS fixture targets IPv4 127.0.0.1. On IPv6-first JVMs,
        // getLoopbackAddress() can instead bind ::1 and strand both DNS servers.
        private val ipv4Loopback = InetAddress.getByName("127.0.0.1")
        private val tcp = ServerSocket(0, 1, ipv4Loopback)
        private val udp = DatagramSocket(InetSocketAddress(ipv4Loopback, tcp.localPort))
        val key = FlowKey(0x0a000001, 53001, 0x7f000001, tcp.localPort)
        val tcpQueries = LinkedBlockingQueue<ByteArray>()
        init {
            Thread({
                try {
                    while (!udp.isClosed) {
                        val packet = DatagramPacket(ByteArray(512), 512)
                        udp.receive(packet)
                        val reply = answer(packet.data.copyOf(packet.length), false)
                        udp.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                    }
                } catch (_: IOException) { /* closing fixture unblocks receive */ }
            }, "test-dns-udp-server").apply { isDaemon = true; start() }
            Thread({
                try {
                    tcp.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val question = ByteArray(input.readUnsignedShort()).also(input::readFully)
                        tcpQueries.add(question)
                        socket.getOutputStream().write(frame(answer(question, true)))
                    }
                } catch (_: IOException) { /* closing fixture unblocks accept */ }
            }, "test-dns-tcp-server").apply { isDaemon = true; start() }
        }
        fun udpFlow(write: (ByteArray) -> Unit) = UdpFlow(key,
            "127.0.0.1", proxy.proxy.boundPort, proxy.password, { true }, { true }, write, {}, {}, {})
        override fun close() { udp.close(); tcp.close(); proxy.close() }
    }

    companion object {
        private fun query(type: Int): ByteArray = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeShort(type); writeShort(0x0100); writeShort(1)
                writeShort(0); writeShort(0); writeShort(0)
                for (label in listOf("example", "test")) { writeByte(label.length); writeBytes(label) }
                writeByte(0); writeShort(type); writeShort(1)
            }
        }.toByteArray()

        private fun answer(question: ByteArray, tcp: Boolean): ByteArray {
            val type = question[question.size - 3].toInt() and 255
            val truncated = type == 16 && !tcp
            val data = when (type) {
                1 -> byteArrayOf(192.toByte(), 0, 2, 1)
                28 -> InetAddress.getByName("2001:db8::1").address
                else -> ByteArrayOutputStream().apply {
                    repeat(8) { write(250); write(ByteArray(250) { 88 }) }
                }.toByteArray()
            }
            return ByteArrayOutputStream().apply {
                DataOutputStream(this).apply {
                    write(question, 0, 2); writeShort(if (truncated) 0x8380 else 0x8180)
                    writeShort(1); writeShort(if (truncated) 0 else 1); writeShort(0); writeShort(0)
                    write(question, 12, question.size - 12)
                    if (!truncated) {
                        writeShort(0xc00c); writeShort(type); writeShort(1); writeInt(60)
                        writeShort(data.size); write(data)
                    }
                }
            }.toByteArray()
        }
        private fun frame(message: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeShort(message.size); write(message) }
        }.toByteArray()
    }
}

package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UdpFlowTest {
    @Test fun oversizeReplyIsCountedAndNextReplyStillArrives() = ProxyFixture().use { fixture ->
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { server ->
            Thread({
                try {
                    repeat(3) {
                        val query = DatagramPacket(ByteArray(1), 1)
                        server.receive(query)
                        val size = when (query.data[0].toInt()) { 1 -> 1472; 2 -> 1473; else -> 100 }
                        val response = ByteArray(size) { query.data[0] }
                        server.send(DatagramPacket(response, response.size, query.socketAddress))
                    }
                } catch (_: java.io.IOException) { /* test cleanup closes receive */ }
            }, "test-udp-mtu-boundary").apply { isDaemon = true; start() }
            val output = LinkedBlockingQueue<ByteArray>()
            val oversized = CountDownLatch(1)
            val delivered = CountDownLatch(2)
            val drops = AtomicInteger()
            val received = AtomicInteger()
            val errors = LinkedBlockingQueue<String>()
            val flow = UdpFlow(FlowKey(0x0a000001, 10003, 0x7f000001, server.localPort),
                "127.0.0.1", fixture.proxy.boundPort, fixture.password, { true }, { true },
                { output.add(it) }, { received.addAndGet(it); delivered.countDown() }, {}, {},
                onError = { errors.add(it) },
                onOversizedReply = { drops.incrementAndGet(); oversized.countDown() })
            try {
                flow.send(byteArrayOf(1))
                Thread({ flow.start() }, "test-udp-mtu-flow").apply { isDaemon = true; start() }
                val fullMtu = output.poll(5, TimeUnit.SECONDS)!!
                assertEquals(1500, fullMtu.size)
                assertArrayEquals(ByteArray(1472) { 1 }, Ipv4Packet.parse(fullMtu)!!.payload)
                flow.send(byteArrayOf(2))
                assertTrue("oversize UDP reply must be diagnosed", oversized.await(3, TimeUnit.SECONDS))
                flow.send(byteArrayOf(3))
                assertArrayEquals(ByteArray(100) { 3 },
                    Ipv4Packet.parse(output.poll(3, TimeUnit.SECONDS)!!)!!.payload)
                assertTrue("oversize reply must not produce a malformed TUN packet", output.isEmpty())
                assertTrue(delivered.await(3, TimeUnit.SECONDS))
                assertEquals("count only the single oversize reply", 1, drops.get())
                assertEquals("RX counts only packets delivered to TUN", 1572, received.get())
                assertTrue("oversize replies do not fail the association", errors.isEmpty())
            } finally { flow.close() }
        }
    }

    @Test fun firstPacketsAndTwoAppsUsingSameEndpointArePreserved() = ProxyFixture().use { fixture ->
        val output = LinkedBlockingQueue<Ipv4Packet>()
        fun create(sourcePort: Int) = UdpFlow(FlowKey(0x0a000001, sourcePort, 0x7f000001, fixture.udpPort),
            "127.0.0.1", fixture.proxy.boundPort, fixture.password, { true }, { true },
            { output.add(Ipv4Packet.parse(it)!!) }, {}, {}, {})
        val a = create(10001); val b = create(10002)
        try {
            a.send("first-app".toByteArray()); b.send("second-app".toByteArray())
            Thread({ a.start() }, "test-udp-a").apply { isDaemon = true; start() }
            Thread({ b.start() }, "test-udp-b").apply { isDaemon = true; start() }
            val responses = listOf(output.poll(5, TimeUnit.SECONDS)!!, output.poll(5, TimeUnit.SECONDS)!!)
                .associateBy { it.key.destinationPort }
            assertEquals("first-app", String(responses[10001]!!.payload))
            assertEquals("second-app", String(responses[10002]!!.payload))
            a.send(ByteArray(1400) { 7 })
            assertArrayEquals(ByteArray(1400) { 7 }, output.poll(3, TimeUnit.SECONDS)!!.payload)
        } finally { a.close(); b.close() }
    }
    @Test fun setupFailureIsReportedInsteadOfSilentlyDropped() {
        val errors = mutableListOf<String>()
        val flow = UdpFlow(FlowKey(1, 1111, 2, 53), "127.0.0.1", 8282,
            "never-log-this-secret", { throw java.io.IOException("Private destination, secret") },
            { true }, {}, {}, {}, {}, onError = { errors.add(it) })
        flow.start()
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("UDP proxy setup"))
        assertFalse(errors[0].contains("never-log"))
        assertFalse(errors[0].contains("Private destination"))
    }

    @Test fun fullUdpQueueReportsOverflowInsteadOfSilentDrop() {
        val errors = mutableListOf<String>()
        val flow = UdpFlow(FlowKey(1, 1025, 2, 53), "127.0.0.1", 1,
            "test-only", { true }, { true }, {}, {}, {}, {},
            onError = { errors.add(it) })
        try {
            repeat(64) { flow.send(byteArrayOf(it.toByte())) }
            assertTrue(errors.isEmpty())
            flow.send(byteArrayOf(0x55))
            assertEquals(listOf("UDP packet queue full"), errors)
        } finally { flow.close() }
    }

    @Test fun oversizeOutboundPacketsCannotFillTheBoundedQueue() {
        val errors = mutableListOf<String>()
        val flow = UdpFlow(FlowKey(1, 1025, 2, 53), "127.0.0.1", 1,
            "test-only", { true }, { true }, {}, {}, {}, {},
            onError = { errors.add(it) })
        try {
            flow.send(ByteArray(1473))
            flow.send(ByteArray(65_507))
            assertEquals(listOf("UDP packet exceeds VPN MTU", "UDP packet exceeds VPN MTU"), errors)
            // Rejected payloads must not occupy a queue slot. Exactly 64
            // maximum-MTU payloads still fit; the next packet overflows.
            repeat(64) { flow.send(ByteArray(1472)) }
            assertEquals(2, errors.size)
            flow.send(byteArrayOf(1))
            assertEquals("UDP packet queue full", errors.last())
            assertEquals(3, errors.size)
        } finally { flow.close() }
    }

    @Test fun maximumMtuOutboundUdpPacketStillTraversesTheProxy() = ProxyFixture().use { fixture ->
        val output = LinkedBlockingQueue<Ipv4Packet>()
        val errors = LinkedBlockingQueue<String>()
        val flow = UdpFlow(FlowKey(0x0a000001, 10004, 0x7f000001, fixture.udpPort),
            "127.0.0.1", fixture.proxy.boundPort, fixture.password, { true }, { true },
            { output.add(Ipv4Packet.parse(it)!!) }, {}, {}, {}, onError = { errors.add(it) })
        try {
            val payload = ByteArray(1472) { (it and 255).toByte() }
            flow.send(payload)
            Thread({ flow.start() }, "test-udp-outbound-mtu").apply { isDaemon = true; start() }
            val response = output.poll(5, TimeUnit.SECONDS)
            assertNotNull("max-MTU datagram must still be relayed", response)
            assertArrayEquals(payload, response!!.payload)
            assertTrue(errors.isEmpty())
        } finally { flow.close() }
    }

    @Test fun expiredUdpFlowMayBeEvictedButNotActiveFlow() {
        val flow = UdpFlow(FlowKey(1, 1000, 2, 53), "127.0.0.1", 1,
            "test-only", { true }, { true }, {}, {}, {}, {})
        try {
            assertFalse(flow.idleForAtLeast(System.nanoTime(), 15_000_000_000L))
            assertTrue(flow.idleForAtLeast(System.nanoTime() + 20_000_000_000L, 15_000_000_000L))
            flow.send(byteArrayOf(1))
            assertFalse(flow.idleForAtLeast(System.nanoTime(), 15_000_000_000L))
        } finally { flow.close() }
    }

    @Test fun closeBeforeConnectIsIdempotent() {
        var callbacks = 0
        val flow = UdpFlow(FlowKey(1, 1234, 2, 53), "127.0.0.1", 1, "x", { true }, { true }, {}, {}, {}, { callbacks++ })
        flow.close(); flow.close(); flow.start()
        assertEquals(1, callbacks)
    }
}

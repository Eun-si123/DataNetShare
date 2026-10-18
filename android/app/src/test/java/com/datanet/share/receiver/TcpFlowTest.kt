package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.IpPackets
import com.datanet.share.common.Ipv4Packet
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TcpFlowTest {
    private fun incoming(key: FlowKey, seq: Int, ack: Int, flags: Int = 0x10, window: Int = 4096, bytes: ByteArray = ByteArray(0)) =
        Ipv4Packet(key, 6, seq, ack, flags, window, bytes)

    @Test fun unfinishedTcpHandshakeNeverOccupiesSenderProxySlot() = ProxyFixture().use { fixture ->
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { destination ->
            Thread({
                destination.accept().use { socket ->
                    try { while (socket.getInputStream().read() >= 0) {} }
                    catch (_: java.io.IOException) {}
                }
            }, "test-ack-delayed-target").apply { isDaemon = true; start() }
            val key = FlowKey(0x0a000001, 47003, 0x7f000001, destination.localPort)
            val packets = LinkedBlockingQueue<Ipv4Packet>()
            val connected = AtomicBoolean()
            val flow = TcpFlow(key, 1234, "127.0.0.1", fixture.proxy.boundPort,
                fixture.password, { true }, { packets.add(Ipv4Packet.parse(it)!!) },
                {}, {}, {}, initialSequence = 9000,
                onConnected = { connected.set(true) })
            try {
                flow.start()
                val synAck = packets.poll(2, TimeUnit.SECONDS)!!
                assertEquals(0x12, synAck.flags)
                Thread({ flow.openUpstream() }, "test-deferred-socks").apply { isDaemon = true; start() }
                Thread.sleep(250)
                assertEquals("SYN-only must not open remote SOCKS connections", 0, fixture.proxy.clientCount)
                flow.receive(incoming(key, 1235, 9001))
                val deadline = System.nanoTime() + 3_000_000_000L
                while (!connected.get() && System.nanoTime() < deadline) Thread.sleep(25)
                assertTrue("valid TCP ACK must still start the upstream connection", connected.get())
            } finally { flow.close() }
        }
    }

    @Test fun capacityReclaimPreservesFreshFlowButCanRemoveAbandonedSyn() {
        val key = FlowKey(0x0a000001, 59901, 0x7f000001, 443)
        val packets = LinkedBlockingQueue<Ipv4Packet>()
        val flow = TcpFlow(key, 10, "127.0.0.1", 1, "unused",
            { true }, { packets.add(Ipv4Packet.parse(it)!!) }, {}, {}, {},
            initialSequence = 1000)
        try {
            flow.start()
            assertFalse(flow.reclaimableForCapacity())
            assertFalse(flow.reclaimableForCapacity(System.nanoTime() + 4_000_000_000L))
            assertTrue(flow.reclaimableForCapacity(System.nanoTime() + 7_000_000_000L))
            flow.receive(incoming(key, 11, 1001))
            assertFalse("freshly established TCP cannot be reclaimed", flow.reclaimableForCapacity())
            assertTrue("quiet established TCP is reclaimable under pressure",
                flow.reclaimableForCapacity(System.nanoTime() + 22_000_000_000L))
        } finally { flow.close() }
    }

    @Test fun scaledUploadBufferAcceptsMoreThan64KiBButNeverBeyond256KiB() {
        val key = FlowKey(0x0a000001, 50009, 0x08080808, 443)
        val responses = LinkedBlockingQueue<Ipv4Packet>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "unused", { true },
            { responses.add(Ipv4Packet.parse(it)!!) }, {}, { count.addAndGet(it) }, {},
            initialSequence = 1000, peerWindowScale = 7)
        try {
            flow.start()
            assertEquals(0x12, responses.poll(1, TimeUnit.SECONDS)!!.flags)
            flow.receive(incoming(key, 101, 1001))
            var appSequence = 101
            repeat(200) {
                flow.receive(incoming(key, appSequence, 1001, 0x18,
                    window = 2048, bytes = ByteArray(1400) { 0x42 }))
                // 262140 can hold exactly 187 chunks of 1400 bytes. Never
                // ACK bytes we cannot buffer; the peer will retry later.
                if (it < 187) appSequence += 1400
            }
            assertEquals(187 * 1400, count.get())
            assertEquals(appSequence, responses.toList().last().acknowledgement)
            assertEquals((262140 - 187 * 1400) / 4, responses.toList().last().window)
        } finally { flow.close() }
    }

    @Test fun legacyUploadDoesNotAdvertiseScalingOrOverflowQueue() {
        val key = FlowKey(0x0a000001, 50008, 0x08080808, 443)
        val responses = LinkedBlockingQueue<Ipv4Packet>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "unused", { true },
            { responses.add(Ipv4Packet.parse(it)!!) }, {}, { count.addAndGet(it) }, {},
            initialSequence = 1000)
        try {
            flow.start()
            assertEquals(4, responses.poll(1, TimeUnit.SECONDS)!!.tcpOptions.size)
            flow.receive(incoming(key, 101, 1001))
            var appSequence = 101
            repeat(60) {
                flow.receive(incoming(key, appSequence, 1001, 0x18,
                    bytes = ByteArray(1400) { 0x44 }))
                if (it < 46) appSequence += 1400
            }
            assertEquals(46 * 1400, count.get())
            assertEquals(65535 - 46 * 1400, responses.toList().last().window)
        } finally { flow.close() }
    }

    @Test fun lostSynAckIsRetransmittedWithSameSequence() {
        val retransmissions = java.util.concurrent.atomic.AtomicInteger()
        val packets = LinkedBlockingQueue<Ipv4Packet>()
        val key = FlowKey(0x0a000001, 2000, 0x7f000001, 80)
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "x", { true },
            { packets.add(Ipv4Packet.parse(it)!!) }, {}, {}, {}, initialSequence = -2,
            onRetransmit = { retransmissions.incrementAndGet() })
        try {
            flow.start()
            val syn = packets.poll(1, TimeUnit.SECONDS)!!
            flow.tick(System.nanoTime() + 2_000_000_000L)
            val repeat = packets.poll(1, TimeUnit.SECONDS)!!
            assertEquals(syn.sequence, repeat.sequence); assertEquals(0x12, repeat.flags)
            assertEquals(101, repeat.acknowledgement)
            assertEquals("only actual resend increments retransmission metric", 1, retransmissions.get())
        } finally { flow.close() }
    }

    @Test fun halfCloseDuplicateUploadAndLostResponseRemainCorrect() = ProxyFixture().use { fixture ->
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { remote ->
            val request = LinkedBlockingQueue<ByteArray>()
            val response = ByteArray(8000) { (it * 17).toByte() }
            Thread({
                remote.accept().use { socket ->
                    socket.soTimeout = 5000
                    request.add(socket.getInputStream().readBytes())
                    socket.getOutputStream().write(response)
                }
            }, "test-tcp-destination").apply { isDaemon = true; start() }
            val key = FlowKey(0x0a000001, 2000, 0x7f000001, remote.localPort)
            val packets = LinkedBlockingQueue<Ipv4Packet>()
            val closed = AtomicBoolean()
            val flow = TcpFlow(key, 100, "127.0.0.1", fixture.proxy.boundPort, fixture.password, { true },
                { packets.add(Ipv4Packet.parse(it)!!) }, {}, {}, { closed.set(true) }, initialSequence = -3)
            try {
                flow.start(); val syn = packets.poll(1, TimeUnit.SECONDS)!!
                val expectedServerSequence = syn.sequence + 1
                flow.receive(incoming(key, 101, expectedServerSequence))
                Thread({ flow.openUpstream() }, "test-flow-upstream").apply { isDaemon = true; start() }
                flow.receive(incoming(key, 101, expectedServerSequence, 0x18, bytes = "hello".toByteArray()))
                flow.receive(incoming(key, 101, expectedServerSequence, 0x18, bytes = "hello".toByteArray()))
                flow.receive(incoming(key, 106, expectedServerSequence, 0x11))
                assertArrayEquals("hello".toByteArray(), request.poll(5, TimeUnit.SECONDS))
                var next = expectedServerSequence
                val output = java.io.ByteArrayOutputStream()
                var dropped: Ipv4Packet? = null
                var sawFin = false
                val deadline = System.nanoTime() + 8_000_000_000L
                while (!sawFin && System.nanoTime() < deadline) {
                    val packet = packets.poll(100, TimeUnit.MILLISECONDS)
                    if (packet != null && (packet.payload.isNotEmpty() || packet.flags and 1 != 0)) {
                        if (dropped == null && packet.payload.isNotEmpty()) {
                            dropped = packet
                            // Hold ACKs until an actual retransmission of the first missing segment arrives.
                            continue
                        }
                        if (packet.sequence == next) {
                            output.write(packet.payload); next += packet.payload.size
                            if (packet.flags and 1 != 0) { next++; sawFin = true }
                        }
                        flow.receive(incoming(key, 107, next))
                    }
                    flow.tick()
                }
                assertTrue("remote FIN was not delivered", sawFin)
                assertArrayEquals(response, output.toByteArray())
            } finally { flow.close() }
        }
    }
    @Test fun outOfOrderDataAndFinDoNotAdvanceAcknowledgement() {
        val packets = LinkedBlockingQueue<Ipv4Packet>()
        val key = FlowKey(0x0a000001, 2000, 0x7f000001, 80)
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "x", { true }, { packets.add(Ipv4Packet.parse(it)!!) }, {}, {}, {}, initialSequence = 1000)
        try {
            flow.start(); packets.clear()
            flow.receive(incoming(key, 101, 1001))
            flow.receive(incoming(key, 200, 1001, 0x19, bytes = byteArrayOf(1, 2, 3)))
            assertEquals(101, packets.poll(1, TimeUnit.SECONDS)!!.acknowledgement)
        } finally { flow.close() }
    }
    /**
     * Browser speed tests transfer far more than the small half-close fixture.
     * Simulate a continuous multi-megabyte download with explicit app TCP ACKs,
     * checking every byte and confirming a clean remote FIN without RST.
     */
    @Test fun sustainedMultiMegabyteDownloadDoesNotResetOrCorrupt() = ProxyFixture().use { fixture ->
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val payload = ByteArray(2_097_177) { ((it * 31 + it / 257) and 255).toByte() }
            val senderFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            Thread({
                try {
                    server.accept().use { remote ->
                        remote.getOutputStream().write(payload)
                    }
                } catch (e: Throwable) { senderFailure.set(e) }
            }, "test-large-download").apply { isDaemon = true; start() }
            val key = FlowKey(0x0a000001, 28333, 0x7f000001, server.localPort)
            val packets = LinkedBlockingQueue<Ipv4Packet>()
            val errors = LinkedBlockingQueue<String>()
            val flow = TcpFlow(key, 9000, "127.0.0.1", fixture.proxy.boundPort,
                fixture.password, { true },
                { bytes -> packets.add(Ipv4Packet.parse(bytes)!!) },
                {}, {}, {}, initialSequence = 1000,
                onError = { errors.add(it) })
            try {
                flow.start()
                val syn = packets.poll(2, TimeUnit.SECONDS)!!
                assertEquals(0x12, syn.flags)
                var acknowledged = syn.sequence + 1
                flow.receive(incoming(key, 9001, acknowledged, window = 32768))
                Thread({ flow.openUpstream() }, "test-large-upstream").apply { isDaemon = true; start() }
                val result = java.io.ByteArrayOutputStream(payload.size)
                var fin = false
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!fin && System.nanoTime() < deadline) {
                    val packet = packets.poll(100, TimeUnit.MILLISECONDS)
                    if (packet == null) { flow.tick(); continue }
                    assertEquals("unexpected TCP RST during sustained download", 0,
                        packet.flags and 4)
                    if (packet.payload.isEmpty() && packet.flags and 1 == 0) continue
                    assertEquals("broken sequence during sustained download", acknowledged,
                        packet.sequence)
                    assertFalse("unacknowledged download must never be reclaimed",
                        flow.reclaimableForCapacity(System.nanoTime() + 22_000_000_000L))
                    result.write(packet.payload)
                    acknowledged += packet.payload.size
                    if (packet.flags and 1 != 0) { acknowledged++; fin = true }
                    flow.receive(incoming(key, 9001, acknowledged, window = 32768))
                    flow.tick()
                }
                assertTrue("remote FIN not received within test window", fin)
                assertArrayEquals(payload, result.toByteArray())
                assertNull("upstream server failed", senderFailure.get())
                assertTrue("flow errors: $errors", errors.isEmpty())
            } finally { flow.close() }
        }
    }

    @Test fun tripleDuplicateAckTriggersFastRetransmitBeforeRto() = ProxyFixture().use { fixture ->
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Thread({
                server.accept().use { socket ->
                    socket.getOutputStream().write(ByteArray(5000) { (it and 255).toByte() })
                    socket.getOutputStream().flush()
                }
            }, "test-fast-retransmit").apply { isDaemon = true; start() }
            val key = FlowKey(0x0a000001, 47231, 0x7f000001, server.localPort)
            val packets = LinkedBlockingQueue<Ipv4Packet>()
            val count = java.util.concurrent.atomic.AtomicInteger()
            val flow = TcpFlow(key, 1000, "127.0.0.1", fixture.proxy.boundPort,
                fixture.password, { true }, { packets.add(Ipv4Packet.parse(it)!!) },
                {}, {}, {}, initialSequence = 5000,
                onRetransmit = { count.incrementAndGet() })
            try {
                flow.start()
                assertEquals(0x12, packets.poll(2, TimeUnit.SECONDS)!!.flags)
                flow.receive(incoming(key, 1001, 5001, window = 32768))
                Thread({ flow.openUpstream() }, "test-fast-upstream").apply { isDaemon = true; start() }
                val first = packets.poll(5, TimeUnit.SECONDS)!!
                assertTrue("expected upstream data", first.payload.isNotEmpty())
                val before = count.get()
                repeat(2) { flow.receive(incoming(key, 1001, 5001, window = 32768)) }
                assertEquals("do not fast-retransmit before third duplicate ACK", before, count.get())
                flow.receive(incoming(key, 1001, 5001, window = 32768))
                assertEquals(before + 1, count.get())
                val sent = mutableListOf<Ipv4Packet>()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (System.nanoTime() < deadline && sent.none {
                    it.sequence == first.sequence && it.payload.contentEquals(first.payload)
                }) {
                    packets.poll(100, TimeUnit.MILLISECONDS)?.let(sent::add)
                }
                assertTrue("lost segment must be retransmitted immediately",
                    sent.any { it.sequence == first.sequence && it.payload.contentEquals(first.payload) })
            } finally { flow.close() }
        }
    }

    @Test fun peerWithSmallAdvertisedMssNeverReceivesOversizedTcpSegments() =
        ProxyFixture().use { fixture ->
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                val bytes = ByteArray(2500) { (it % 251).toByte() }
                Thread({ server.accept().use { it.getOutputStream().write(bytes) } },
                    "test-limited-mss-server").apply { isDaemon = true; start() }
                val key = FlowKey(0x0a000001, 50001, 0x7f000001, server.localPort)
                val packets = LinkedBlockingQueue<Ipv4Packet>()
                val flow = TcpFlow(key, 100, "127.0.0.1", fixture.proxy.boundPort,
                    fixture.password, { true }, { packets.add(Ipv4Packet.parse(it)!!) },
                    {}, {}, {}, initialSequence = 4000, peerMss = 600)
                try {
                    flow.start()
                    assertEquals(0x12, packets.poll(2, TimeUnit.SECONDS)!!.flags)
                    flow.receive(incoming(key, 101, 4001, window = 65535))
                    Thread({ flow.openUpstream() }, "test-mss-connect")
                        .apply { isDaemon = true; start() }
                    var next = 4001
                    val captured = java.io.ByteArrayOutputStream()
                    val deadline = System.nanoTime() + 8_000_000_000L
                    while (captured.size() < bytes.size && System.nanoTime() < deadline) {
                        val packet = packets.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        if (packet.payload.isNotEmpty()) {
                            assertTrue("must respect peer MSS", packet.payload.size <= 600)
                            assertEquals(next, packet.sequence)
                            captured.write(packet.payload)
                            next += packet.payload.size
                            flow.receive(incoming(key, 101, next, window = 65535))
                        }
                    }
                    assertArrayEquals(bytes, captured.toByteArray())
                } finally { flow.close() }
            }
        }

    @Test fun zeroReceiveWindowPausesDownloadsUntilWindowUpdate() = ProxyFixture().use { fixture ->
        ServerSocket(0).use { destination ->
            Thread({ destination.accept().use { it.getOutputStream().write(ByteArray(2048) { 7 }) } }, "test-window").apply { isDaemon = true; start() }
            val key = FlowKey(0x0a000001, 2010, 0x7f000001, destination.localPort)
            val packets = LinkedBlockingQueue<Ipv4Packet>()
            val flow = TcpFlow(key, 100, "127.0.0.1", fixture.proxy.boundPort, fixture.password, { true },
                { packets.add(Ipv4Packet.parse(it)!!) }, {}, {}, {}, initialSequence = 1000)
            try {
                flow.start(); packets.clear()
                flow.receive(incoming(key, 101, 1001, window = 0))
                Thread({ flow.openUpstream() }, "test-window-connect").apply { isDaemon = true; start() }
                assertNull(packets.poll(250, TimeUnit.MILLISECONDS))
                flow.receive(incoming(key, 101, 1001, window = 512))
                val first = packets.poll(3, TimeUnit.SECONDS)!!
                assertEquals(512, first.payload.size)
                flow.receive(incoming(key, 101, 1513, window = 0))
                assertNull(packets.poll(250, TimeUnit.MILLISECONDS))
                flow.receive(incoming(key, 101, 1513, window = 512))
                val second = packets.poll(3, TimeUnit.SECONDS)!!
                assertEquals(1513, second.sequence); assertEquals(512, second.payload.size)
            } finally { flow.close() }
        }
    }

}

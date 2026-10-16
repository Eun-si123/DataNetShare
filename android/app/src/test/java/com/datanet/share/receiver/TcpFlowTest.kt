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

    @Test fun lostSynAckIsRetransmittedWithSameSequence() {
        val packets = LinkedBlockingQueue<Ipv4Packet>()
        val key = FlowKey(0x0a000001, 2000, 0x7f000001, 80)
        val flow = TcpFlow(key, 100, "127.0.0.1", 1, "x", { true },
            { packets.add(Ipv4Packet.parse(it)!!) }, {}, {}, {}, initialSequence = -2)
        try {
            flow.start()
            val syn = packets.poll(1, TimeUnit.SECONDS)!!
            flow.tick(System.nanoTime() + 2_000_000_000L)
            val repeat = packets.poll(1, TimeUnit.SECONDS)!!
            assertEquals(syn.sequence, repeat.sequence); assertEquals(0x12, repeat.flags)
            assertEquals(101, repeat.acknowledgement)
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

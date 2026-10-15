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

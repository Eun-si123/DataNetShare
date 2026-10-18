package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TcpLossRecoveryTest {
    @Test fun stableWindowRetransmitsOnThirdDuplicateAckWithOrWithoutScaling() {
        for (shift in listOf(null, 0, 2, 7, 14)) {
            Download(shift, 8192).use { download ->
                repeat(2) { download.ack(8192) }
                assertEquals("no early retransmit (shift=$shift)", 0, download.retransmits.get())
                download.ack(8192)
                assertEquals("third duplicate must retransmit without a timer (shift=$shift)",
                    1, download.retransmits.get())
                download.ack(8192)
                assertEquals("fourth duplicate must not resend again", 1, download.retransmits.get())
                download.assertFirstSegmentRetransmitted()
            }
        }
    }

    @Test fun changingWireWindowResetsDuplicatesEvenAboveTheBufferCap() {
        // Both windows scale beyond the 262140-byte local send cap. Comparing
        // only the capped effective window would misclassify a real update.
        Download(14, 32).use { download ->
            repeat(2) { download.ack(32) }
            download.ack(33)
            assertEquals("a window update is not a third duplicate ACK", 0, download.retransmits.get())
            repeat(2) { download.ack(33) }
            assertEquals("window update must reset the duplicate count", 0, download.retransmits.get())
            download.ack(33)
            assertEquals(1, download.retransmits.get())
            download.assertFirstSegmentRetransmitted()
        }
    }

    @Test fun finAndDataDoNotCountAsDuplicateAcks() {
        for (shift in listOf(null, 7)) {
            Download(shift, 8192).use { download ->
                repeat(2) { download.ack(8192) }
                // Out-of-order FIN: it must neither close the upload nor count
                // as the third duplicate. A retransmitted FIN is not a dupACK.
                repeat(3) { download.ack(8192, flags = 0x11, sequence = 1002) }
                assertEquals("FIN is not a duplicate ACK", 0, download.retransmits.get())
                repeat(2) { download.ack(8192) }
                download.ack(8192, flags = 0x18, payload = byteArrayOf(7))
                assertEquals("payload is not a duplicate ACK", 0, download.retransmits.get())
                repeat(2) { download.ack(8192, sequence = 1002) }
                assertEquals("payload must reset the duplicate count", 0, download.retransmits.get())
                download.ack(8192, sequence = 1002)
                assertEquals(1, download.retransmits.get())
            }
        }
    }

    /** Real upstream bytes; ACKs are injected without running the RTO timer. */
    private class Download(shift: Int?, initialWindow: Int) : AutoCloseable {
        private val fixture = ProxyFixture()
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val key = FlowKey(0x0a000001, 47232, 0x7f000001, server.localPort)
        private val packets = LinkedBlockingQueue<Ipv4Packet>()
        val retransmits = AtomicInteger()
        private val flow = TcpFlow(key, 1000, "127.0.0.1", fixture.proxy.boundPort,
            fixture.password, { true }, { packets.add(Ipv4Packet.parse(it)!!) },
            {}, {}, {}, initialSequence = 5000, peerWindowScale = shift,
            onRetransmit = { retransmits.incrementAndGet() })
        private val first: Ipv4Packet

        init {
            try {
                Thread({
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        socket.getOutputStream().write(ByteArray(5600) { (it % 251).toByte() })
                        socket.getInputStream().readBytes()
                    }
                }, "test-loss-destination").apply { isDaemon = true; start() }
                flow.start()
                assertEquals(0x12, packets.poll(2, TimeUnit.SECONDS)!!.flags)
                ack(initialWindow)
                Thread({ flow.openUpstream() }, "test-loss-upstream")
                    .apply { isDaemon = true; start() }
                first = packets.poll(5, TimeUnit.SECONDS)!!
                assertTrue("expected upstream data", first.payload.isNotEmpty())
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        fun ack(window: Int, flags: Int = 0x10, sequence: Int = 1001,
                payload: ByteArray = ByteArray(0)) {
            flow.receive(Ipv4Packet(key, 6, sequence, 5001, flags, window, payload))
        }

        fun assertFirstSegmentRetransmitted() {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val packet = packets.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (packet.sequence == first.sequence && packet.payload.contentEquals(first.payload)) return
            }
            fail("missing immediate retransmission of the original segment")
        }

        override fun close() { flow.close(); server.close(); fixture.close() }
    }
}

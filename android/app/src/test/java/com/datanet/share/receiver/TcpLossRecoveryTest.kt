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

    @Test fun zeroWindowPreservesOutstandingDataUntilWindowReopens() {
        for (shift in listOf(null, 2)) {
            // Bound the original in-flight data to one MSS.
            val window = if (shift == null) 1400 else 350
            Download(shift, window).use { download ->
                download.ack(0)
                // A stream read may split that MSS into several original packets.
                // The zero-window update prevents further data emission under the lock.
                download.clearPackets()
                val now = System.nanoTime()
                repeat(9) {
                    download.tick(now + (it + 1) * 4_000_000_000L)
                    download.ack(0) // Persist responses are not duplicate loss ACKs.
                }
                download.assertOpen()
                assertEquals("persist must not spend data retries", 0, download.retransmits.get())

                // Neither an old ACK nor an ACK beyond sent data may reopen the window.
                download.ack(window, acknowledgement = 5000)
                download.ack(window, acknowledgement = 6402)
                download.tick(now + 40_000_000_000L)
                download.assertProbes(10)
                assertEquals(0, download.retransmits.get())

                download.ack(window)
                repeat(2) { download.ack(window) }
                download.tick()
                assertEquals("reopening starts a fresh RTO and duplicate-ACK count",
                    0, download.retransmits.get())
                download.tick(System.nanoTime() + 600_000_000L)
                assertEquals(1, download.retransmits.get())
                download.assertFirstSegmentRetransmitted()
                download.assertOpen()
            }
        }
    }

    @Test fun zeroWindowPausesButDoesNotResetExistingRetryBudget() {
        Download(null, 1400).use { download ->
            download.tick(System.nanoTime() + 4_000_000_000L)
            assertEquals(1, download.retransmits.get())
            download.assertFirstSegmentRetransmitted()
            download.ack(0)
            val persistedAt = System.nanoTime()
            repeat(9) { download.tick(persistedAt + (it + 1) * 4_000_000_000L) }
            download.assertOpen()
            download.ack(1400)
            val reopenedAt = System.nanoTime()
            download.tick(reopenedAt + 600_000_000L)
            assertEquals("keep the one-second backoff already earned", 1, download.retransmits.get())
            download.tick(reopenedAt + 1_100_000_000L)
            assertEquals(2, download.retransmits.get())
            repeat(6) { download.tick(reopenedAt + 5_100_000_000L + it * 4_000_000_000L) }
            download.assertOpen()
            assertEquals(8, download.retransmits.get())
            download.tick(reopenedAt + 29_100_000_000L)
            download.assertClosedWith("TCP peer did not acknowledge retransmissions")
        }
    }

    @Test fun zeroWindowStillExpiresWhenPeerGoesIdle() {
        Download(null, 1400).use { download ->
            download.ack(0)
            download.tick(System.nanoTime() + TcpCapacityPolicy.IDLE_TIMEOUT_NANOS + 1_000_000L)
            download.assertClosedWith("TCP flow idle timeout")
        }
    }

    @Test fun zeroWindowDoesNotSuspendSynOrFinRetransmission() {
        val key = FlowKey(0x0a000001, 47233, 0x7f000001, 80)
        val packets = LinkedBlockingQueue<Ipv4Packet>()
        val flow = TcpFlow(key, 1000, "127.0.0.1", 1, "unused", { true },
            { packets.add(Ipv4Packet.parse(it)!!) }, {}, {}, {}, initialSequence = 5000)
        try {
            flow.start()
            assertEquals(0x12, packets.poll(2, TimeUnit.SECONDS)!!.flags)
            // This ACK does not acknowledge the SYN, but advertises a zero window.
            flow.receive(Ipv4Packet(key, 6, 1001, 5000, 0x10, 0, ByteArray(0)))
            flow.tick(System.nanoTime() + 600_000_000L)
            assertEquals(0x12, packets.poll(2, TimeUnit.SECONDS)!!.flags)
        } finally { flow.close() }

        Download(null, 1400, payloadSize = 0, finishOutput = true).use { download ->
            download.ack(0)
            download.tick(System.nanoTime() + 600_000_000L)
            assertEquals(1, download.retransmits.get())
            download.assertFirstSegmentRetransmitted()
            download.assertOpen()
        }
    }

    /** Real upstream bytes with injected ACKs and explicit maintenance times. */
    private class Download(shift: Int?, initialWindow: Int, payloadSize: Int = 5600,
                           finishOutput: Boolean = false) : AutoCloseable {
        private val fixture = ProxyFixture()
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val key = FlowKey(0x0a000001, 47232, 0x7f000001, server.localPort)
        private val packets = LinkedBlockingQueue<Ipv4Packet>()
        val retransmits = AtomicInteger()
        private val closed = AtomicInteger()
        private val errors = LinkedBlockingQueue<String>()
        private val flow = TcpFlow(key, 1000, "127.0.0.1", fixture.proxy.boundPort,
            fixture.password, { true }, { packets.add(Ipv4Packet.parse(it)!!) },
            {}, {}, { closed.incrementAndGet() }, initialSequence = 5000, peerWindowScale = shift,
            onError = { errors.add(it) },
            onRetransmit = { retransmits.incrementAndGet() })
        private val first: Ipv4Packet

        init {
            try {
                Thread({
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        socket.getOutputStream().write(ByteArray(payloadSize) { (it % 251).toByte() })
                        if (finishOutput) socket.shutdownOutput()
                        socket.getInputStream().readBytes()
                    }
                }, "test-loss-destination").apply { isDaemon = true; start() }
                flow.start()
                assertEquals(0x12, packets.poll(2, TimeUnit.SECONDS)!!.flags)
                ack(initialWindow)
                Thread({ flow.openUpstream() }, "test-loss-upstream")
                    .apply { isDaemon = true; start() }
                first = packets.poll(5, TimeUnit.SECONDS)!!
                if (payloadSize == 0 && finishOutput) assertEquals(0x11, first.flags)
                else assertTrue("expected upstream data", first.payload.isNotEmpty())
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        fun ack(window: Int, flags: Int = 0x10, sequence: Int = 1001,
                payload: ByteArray = ByteArray(0), acknowledgement: Int = 5001) {
            flow.receive(Ipv4Packet(key, 6, sequence, acknowledgement, flags, window, payload))
        }

        fun tick(now: Long = System.nanoTime()) = flow.tick(now)

        fun clearPackets() = packets.clear()

        fun assertOpen() {
            assertEquals("flow unexpectedly closed: $errors", 0, closed.get())
            assertTrue("flow errors: $errors", errors.isEmpty())
        }

        fun assertClosedWith(error: String) {
            assertEquals(1, closed.get())
            assertEquals(listOf(error), errors.toList())
        }

        fun assertProbes(count: Int) {
            val emitted = mutableListOf<Ipv4Packet>()
            packets.drainTo(emitted)
            assertEquals("one bounded probe per maintenance interval", count, emitted.size)
            assertTrue("persist must emit only ACK probes", emitted.all {
                it.flags == 0x10 && it.payload.isEmpty()
            })
        }

        fun assertFirstSegmentRetransmitted() {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val packet = packets.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (packet.sequence == first.sequence && packet.flags == first.flags &&
                    packet.payload.contentEquals(first.payload)) return
            }
            fail("missing immediate retransmission of the original segment")
        }

        override fun close() { flow.close(); server.close(); fixture.close() }
    }
}


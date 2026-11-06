package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TcpFlowHandshakeInterruptTest {
    @Test fun interruptedHandshakeWaitReleasesFlowWithoutOpeningSocksConnection() {
        val closes = AtomicInteger()
        val protects = AtomicInteger()
        val emitted = AtomicInteger()
        val interruptedOnExit = AtomicBoolean()
        val uncaught = AtomicReference<Throwable?>()
        val flow = TcpFlow(FlowKey(0x0a000001, 50042, 0x7f000001, 443),
            100, "127.0.0.1", 1, "unused",
            { protects.incrementAndGet(); true },
            { emitted.incrementAndGet(); assertNotNull(Ipv4Packet.parse(it)) },
            {}, {}, { closes.incrementAndGet() }, initialSequence = 1000)
        try {
            flow.start() // Send SYN-ACK; the app never acknowledges it.
            val worker = Thread({
                try { flow.openUpstream() } catch (t: Throwable) { uncaught.set(t) }
                interruptedOnExit.set(Thread.currentThread().isInterrupted)
            }, "test-tcp-handshake-interrupt").apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (worker.state != Thread.State.TIMED_WAITING &&
                worker.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("worker must be waiting for the TCP handshake", Thread.State.TIMED_WAITING, worker.state)
            worker.interrupt()
            worker.join(2_000)
            assertFalse("cancelled worker must terminate", worker.isAlive)
            assertNull("cancelled handshake should not throw from worker", uncaught.get())
            assertTrue("cancellation must preserve the interrupt signal", interruptedOnExit.get())
            assertEquals("cancelled flow must release its admission slot", 1, closes.get())
            assertEquals("no SOCKS socket should be protected/connected", 0, protects.get())
            assertEquals("do not send RST for a cancelled handshake", 1, emitted.get())
        } finally { flow.close() }
    }
}

package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A cancelled UDP worker must release its SOCKS association without losing interruption. */
class UdpFlowInterruptTest {
    @Test fun interruptedRelayWorkerClosesOnceAndPreservesInterruptSignal() = ProxyFixture().use { fixture ->
        val connected = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val errors = LinkedBlockingQueue<String>()
        val flow = UdpFlow(
            FlowKey(0x0a000001, 18001, 0x7f000001, fixture.udpPort),
            "127.0.0.1", fixture.proxy.boundPort, fixture.password,
            { true }, { true }, {}, {}, {},
            { closes.incrementAndGet(); closed.countDown() },
            onError = { errors.add(it) },
            onConnected = { connected.countDown() }
        )
        val worker = Thread({ flow.start() }, "test-udp-interrupt")
        worker.isDaemon = true
        try {
            worker.start()
            assertTrue("SOCKS association must be ready", connected.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue("interrupted worker must release its association", closed.await(5, TimeUnit.SECONDS))
            worker.join(3000)
            assertFalse("interrupted worker must exit", worker.isAlive)
            assertTrue("InterruptedException must not erase cancellation signal", worker.isInterrupted)
            assertEquals(1, closes.get())
            assertEquals(listOf("UDP relay interrupted. Reconnect to resume."), errors.toList())
            assertFalse(errors.any { it.contains(fixture.password) })
        } finally {
            worker.interrupt()
            flow.close()
            worker.join(3000)
        }
    }
}

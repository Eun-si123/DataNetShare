package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Inject child Thread.start failures without exhausting native threads on a device. */
class UdpFlowChildWorkerFailureTest {
    @Test fun firstChildWorkerLaunchFailureClosesBothSocketsAndReportsSafely() = verifyFailure(1, false)

    @Test fun secondChildWorkerLaunchFailureStopsAlreadyStartedControlWorker() = verifyFailure(2, false)

    @Test fun childWorkerSecurityExceptionUsesExistingSanitizedFailurePath() = verifyFailure(1, true)

    private fun verifyFailure(failAt: Int, securityFailure: Boolean) = ProxyFixture().use { fixture ->
        val control = AtomicReference<Socket?>()
        val relay = AtomicReference<DatagramSocket?>()
        val errors = CopyOnWriteArrayList<String>()
        val closes = AtomicInteger()
        val connected = AtomicInteger()
        val launches = AtomicInteger()
        val started = mutableListOf<Thread>()
        val flow = UdpFlow(
            FlowKey(0x0a000001, 18091, 0x7f000001, fixture.udpPort),
            "127.0.0.1", fixture.proxy.boundPort, fixture.password,
            { socket -> control.set(socket); true },
            { socket -> relay.set(socket); true },
            { fail("failed child launch must not write into TUN") },
            {}, {}, { closes.incrementAndGet() },
            onError = { errors.add(it) },
            onConnected = { connected.incrementAndGet() },
            launchWorker = { thread ->
                if (launches.incrementAndGet() == failAt) {
                    if (securityFailure) throw SecurityException("private synthetic details")
                    throw OutOfMemoryError("private synthetic details")
                }
                started.add(thread)
                thread.start()
            }
        )
        try {
            flow.send(byteArrayOf(1))
            flow.start()
            assertEquals(failAt, launches.get())
            assertEquals(1, connected.get())
            assertEquals(1, closes.get())
            assertTrue("control socket must be closed", control.get()?.isClosed == true)
            assertTrue("relay socket must be closed", relay.get()?.isClosed == true)
            assertEquals(1, errors.size)
            val expected = if (securityFailure) "UDP proxy setup/relay failed (SecurityException)"
                else "UDP relay worker could not start. Reconnect to resume."
            assertEquals(expected, errors.single())
            assertFalse(errors.single().contains("private synthetic details"))
            assertFalse(errors.single().contains(fixture.password))
        } finally {
            flow.close()
            started.forEach { it.join(3000); assertFalse("child worker must stop", it.isAlive) }
        }
        assertEquals("close is idempotent", 1, closes.get())
    }
}

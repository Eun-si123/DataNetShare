package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test

class FlowStopCleanupTest {
    @Test fun readFailureNotificationSurvivesCleanupException() {
        val failure = IllegalStateException("cleanup")
        var reported = false
        try {
            FlowStopCleanup.reportAfterCleanup(
                cleanup = { throw failure },
                report = { reported = true }
            )
            fail("cleanup failure must remain observable")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertTrue(reported)
    }

    @Test fun failingCloseStillReleasesOtherFlowsAndBothMaps() {
        val first = IllegalStateException("tcp close")
        val second = IllegalStateException("udp close")
        val closed = mutableListOf<String>()
        try {
            FlowStopCleanup.closeAll(
                listOf("tcp1", "tcp2"), listOf("udp1", "udp2"),
                closeTcp = { closed.add(it); if (it == "tcp1") throw first },
                closeUdp = { closed.add(it); if (it == "udp1") throw second },
                clearTcp = { closed.add("clearTcp") },
                clearUdp = { closed.add("clearUdp") }
            )
            fail("cleanup failure should remain observable")
        } catch (caught: IllegalStateException) {
            assertSame(first, caught)
            assertArrayEquals(arrayOf(second), caught.suppressed)
        }
        assertEquals(listOf("tcp1", "tcp2", "clearTcp", "udp1", "udp2", "clearUdp"), closed)
    }
}

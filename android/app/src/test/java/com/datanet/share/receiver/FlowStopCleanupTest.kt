package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test

class FlowStopCleanupTest {
    @Test fun callbackFailureDoesNotMaskCleanupFailure() {
        val cleanupFailure = IllegalStateException("cleanup")
        val callbackFailure = IllegalArgumentException("callback")
        try {
            FlowStopCleanup.reportAfterCleanup(
                cleanup = { throw cleanupFailure },
                report = { throw callbackFailure }
            )
            fail("original cleanup failure should remain observable")
        } catch (caught: IllegalStateException) {
            assertSame(cleanupFailure, caught)
            assertArrayEquals(arrayOf(callbackFailure), caught.suppressed)
        }
    }

    @Test fun callbackFailureAloneRemainsObservable() {
        val callbackFailure = IllegalArgumentException("callback")
        try {
            FlowStopCleanup.reportAfterCleanup(cleanup = {}, report = { throw callbackFailure })
            fail("callback failure should remain observable")
        } catch (caught: IllegalArgumentException) {
            assertSame(callbackFailure, caught)
        }
    }

    @Test fun identicalCleanupAndCallbackFailureDoesNotSelfSuppress() {
        val failure = IllegalStateException("shared")
        try {
            FlowStopCleanup.reportAfterCleanup(cleanup = { throw failure }, report = { throw failure })
            fail("original failure should remain observable")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
            assertEquals(0, caught.suppressed.size)
        }
    }

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

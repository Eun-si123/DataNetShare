package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class QrPeerProbePolicyTest {
    @Test fun retriesTransientIoAndStopsAfterSuccess() {
        var attempts = 0
        val delays = mutableListOf<Long>()
        assertTrue(QrPeerProbePolicy.reachable({
            attempts++
            if (attempts < 3) throw IOException("synthetic connection failure")
        }, { delays += it }))
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 1_000L), delays)
    }

    @Test fun exhaustedIoFailuresAreBounded() {
        var attempts = 0
        var pauses = 0
        assertFalse(QrPeerProbePolicy.reachable({
            attempts++
            throw IOException("synthetic timeout")
        }, { pauses++ }))
        assertEquals(QrPeerProbePolicy.MAX_ATTEMPTS, attempts)
        assertEquals(QrPeerProbePolicy.MAX_ATTEMPTS - 1, pauses)
    }

    @Test fun platformRuntimeFailuresFailClosedWithoutRetryOrExceptionDetails() {
        for (failure in listOf(SecurityException("synthetic"), IllegalStateException("synthetic"))) {
            var attempts = 0
            var pauses = 0
            assertFalse(QrPeerProbePolicy.reachable({
                attempts++
                throw failure
            }, { pauses++ }))
            assertEquals(1, attempts)
            assertEquals(0, pauses)
        }
    }

    @Test fun interruptionDuringRetryRestoresInterruptFlagAndStops() {
        var attempts = 0
        try {
            assertFalse(QrPeerProbePolicy.reachable({
                attempts++
                throw IOException("synthetic")
            }, { throw InterruptedException("synthetic cancellation") }))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, attempts)
        } finally {
            Thread.interrupted() // Keep the JUnit worker clean for subsequent tests.
        }
    }

    @Test fun alreadyInterruptedWorkerNeverOpensSocket() {
        var attempts = 0
        try {
            Thread.currentThread().interrupt()
            assertFalse(QrPeerProbePolicy.reachable({ attempts++ }, { fail("unexpected pause") }))
            assertEquals(0, attempts)
        } finally {
            Thread.interrupted()
        }
    }
}

package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class QrPeerProbePolicyTest {
    @Test fun transientIoFailuresRetryAndEventuallySucceed() {
        var attempts = 0
        var waits = 0
        assertTrue(QrPeerProbePolicy.reachable(
            connect = { if (++attempts < 3) throw IOException("synthetic route unavailable") },
            delay = { waits++ }
        ))
        assertEquals(3, attempts)
        assertEquals(2, waits)
    }

    @Test fun repeatedIoFailuresExhaustOnlyTheBoundedBudget() {
        var attempts = 0
        var waits = 0
        assertFalse(QrPeerProbePolicy.reachable(
            connect = { attempts++; throw IOException("synthetic route unavailable") },
            delay = { waits++ }
        ))
        assertEquals(QrPeerProbePolicy.MAX_ATTEMPTS, attempts)
        assertEquals(QrPeerProbePolicy.MAX_ATTEMPTS - 1, waits)
    }

    @Test fun platformRuntimeFailureStopsWithoutRetryOrUncaughtWorkerException() {
        var attempts = 0
        assertFalse(QrPeerProbePolicy.reachable(
            connect = { attempts++; throw SecurityException("synthetic permission denial") },
            delay = { fail("security failure must not retry") }
        ))
        assertEquals(1, attempts)
    }

    @Test fun cancellationSkipsSocketWorkAndInterruptedWaitEndsRetry() {
        var attempts = 0
        assertFalse(QrPeerProbePolicy.reachable(
            connect = { attempts++ }, cancelled = { true },
            delay = { fail("cancelled worker must not wait") }
        ))
        assertEquals(0, attempts)
        try {
            assertFalse(QrPeerProbePolicy.reachable(
                connect = { attempts++; throw IOException("synthetic") },
                delay = { throw InterruptedException("synthetic cancellation") }
            ))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, attempts)
        } finally {
            Thread.interrupted() // Clear the test thread's interrupt status.
        }
    }
}

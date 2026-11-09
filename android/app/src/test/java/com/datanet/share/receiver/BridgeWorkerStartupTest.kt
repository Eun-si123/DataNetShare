package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test

class BridgeWorkerStartupTest {
    @Test fun secondWorkerFailureRollsBackAndPreservesOriginalError() {
        val failure = OutOfMemoryError("native worker unavailable")
        var started = false
        var rolledBack = 0
        try {
            BridgeWorkerStartup.launch(
                first = { started = true },
                second = { throw failure },
                rollback = { assertTrue(started); rolledBack++ }
            )
            fail("must propagate worker launch failure")
        } catch (caught: OutOfMemoryError) { assertSame(failure, caught) }
        assertEquals(1, rolledBack)
    }

    @Test fun cleanupFailureDoesNotMaskLaunchFailure() {
        val failure = IllegalStateException("start")
        val cleanup = IllegalStateException("stop")
        try {
            BridgeWorkerStartup.launch({}, { throw failure }, { throw cleanup })
            fail("must propagate original error")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
            assertArrayEquals(arrayOf(cleanup), caught.suppressed)
        }
    }

    @Test fun successDoesNotRollBack() {
        var count = 0
        BridgeWorkerStartup.launch({ count++ }, { count++ }, { fail("unexpected rollback") })
        assertEquals(2, count)
    }
}

package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test

class TunWriteRetryTest {
    @Test fun transientBusyQueueRecoversAfterPoll() {
        var writes = 0
        var waits = 0
        assertTrue(TunWriteRetry.attempt(
            write = { ++writes == 3 },
            awaitWritable = { ++waits; true }
        ))
        assertEquals(3, writes)
        assertEquals(2, waits)
    }

    @Test fun persistentlyCongestedQueueDropsOnePacketAfterBoundedAttempts() {
        var writes = 0
        var waits = 0
        assertFalse(TunWriteRetry.attempt(
            write = { writes++; false },
            awaitWritable = { waits++; true }
        ))
        assertEquals(4, writes)
        assertEquals(3, waits)
    }

    @Test fun noWritableNotificationReturnsWithoutSpinning() {
        var writes = 0
        var waits = 0
        assertFalse(TunWriteRetry.attempt(
            write = { writes++; false },
            awaitWritable = { waits++; false }
        ))
        assertEquals(1, writes)
        assertEquals(1, waits)
    }

    @Test fun successNeverPolls() {
        var waited = false
        assertTrue(TunWriteRetry.attempt(write = { true }, awaitWritable = {
            waited = true
            true
        }))
        assertFalse(waited)
    }

    @Test fun fatalWriteErrorsAreNotHidden() {
        try {
            TunWriteRetry.attempt(write = { throw IllegalStateException("bad TUN fd") },
                awaitWritable = { true })
            fail("fatal TUN error should propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("bad TUN fd", expected.message)
        }
    }

    @Test fun singleAttemptDoesNotWaitAfterItsOnlyWrite() {
        var writes = 0
        var waits = 0
        assertFalse(TunWriteRetry.attempt(maxAttempts = 1,
            write = { writes++; false },
            awaitWritable = { waits++; true }))
        assertEquals(1, writes)
        assertEquals(0, waits)
    }

    @Test fun pollFailurePropagatesInsteadOfBeingMistakenForCongestion() {
        val failure = IllegalStateException("synthetic poll failure")
        var writes = 0
        try {
            TunWriteRetry.attempt(write = { writes++; false },
                awaitWritable = { throw failure })
            fail("fatal poll failure must reach the VPN teardown path")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals(1, writes)
    }

    @Test fun invalidAttemptBudgetsFailBeforeTouchingTheTun() {
        for (invalid in listOf(0, -1, 9, Int.MAX_VALUE)) {
            var writes = 0
            var waits = 0
            try {
                TunWriteRetry.attempt(maxAttempts = invalid,
                    write = { writes++; true },
                    awaitWritable = { waits++; true })
                fail("invalid retry budget $invalid must fail closed")
            } catch (_: IllegalArgumentException) {}
            assertEquals(0, writes)
            assertEquals(0, waits)
        }
    }
}

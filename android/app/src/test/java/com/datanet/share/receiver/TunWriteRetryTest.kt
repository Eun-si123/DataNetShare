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
}

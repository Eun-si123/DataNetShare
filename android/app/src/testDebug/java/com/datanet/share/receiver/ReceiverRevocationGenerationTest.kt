package com.datanet.share.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverRevocationGenerationTest {
    @Test fun revokeForActiveGenerationIsApplied() {
        assertTrue(ReceiverRevocationGeneration.accepts(8, 8))
    }

    @Test fun queuedRevokeAfterStopOrReconnectIsIgnored() {
        // Stop increments generation; reconnect increments it again.
        assertFalse(ReceiverRevocationGeneration.accepts(8, 9))
        assertFalse(ReceiverRevocationGeneration.accepts(8, 10))
    }
}

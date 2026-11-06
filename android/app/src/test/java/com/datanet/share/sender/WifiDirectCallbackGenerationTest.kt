package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test

/** Fake request listener: same acceptance gate used by WifiDirectHost callbacks. */
class WifiDirectCallbackGenerationTest {
    @Test fun stopBeforeCreateSuccessDoesNotResurrectSessionOrAuthorizeGroupRemoval() {
        val generation = WifiDirectCallbackGeneration()
        val token = generation.start()
        var acceptedCreates = 0
        var removalRequests = 0
        fun createCallback() {
            if (generation.accepts(token)) acceptedCreates++
        }
        fun stop() {
            generation.stop()
            // Model old channel: accepted callback alone is not group-formed proof.
            if (acceptedCreates > 0) removalRequests++
        }
        stop()
        createCallback() // Stale Android success callback delivered after Stop.
        assertFalse(generation.running)
        assertFalse(generation.accepts(token))
        assertEquals(0, acceptedCreates)
        assertEquals(0, removalRequests)
    }

    @Test fun lateFailureAndRetryFromPreviousChannelCannotAffectRestartedSession() {
        val generation = WifiDirectCallbackGeneration()
        val first = generation.start()
        generation.stop()
        val second = generation.start()
        var failedCurrent = false
        var retryCurrent = false
        if (generation.accepts(first)) failedCurrent = true
        if (generation.accepts(first)) retryCurrent = true
        assertNotEquals(first, second)
        assertFalse(failedCurrent)
        assertFalse(retryCurrent)
        assertTrue(generation.accepts(second))
    }

    @Test fun activeGroupCreationCallbacksRemainAllowedUntilStop() {
        val generation = WifiDirectCallbackGeneration()
        val token = generation.start()
        assertTrue(generation.accepts(token))
        assertEquals(token, generation.generation)
        generation.stop()
        assertFalse(generation.accepts(token))
        assertFalse(generation.running)
        generation.stop() // Repeated Stop stays idempotent from caller's perspective.
        assertFalse(generation.accepts(token))
    }

    @Test fun concurrentStartWithoutStopFailsRatherThanReusingCallbackToken() {
        val generation = WifiDirectCallbackGeneration()
        val first = generation.start()
        assertThrows(IllegalStateException::class.java) { generation.start() }
        assertEquals(first, generation.generation)
        assertTrue(generation.accepts(first))
    }
}

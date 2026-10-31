package com.datanet.share.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceiverWorkerLaunchGuardTest {
    @Test fun successfulLaunchReturnsTrue() {
        var started = false
        assertTrue(ReceiverWorkerLaunchGuard.start { started = true })
        assertTrue(started)
    }

    @Test fun nativeThreadExhaustionReturnsFailureWithoutExposingError() {
        assertFalse(ReceiverWorkerLaunchGuard.start {
            throw OutOfMemoryError("synthetic native thread limit")
        })
    }

    @Test fun ordinaryFailuresStillReachExistingServiceFailureHandling() {
        val failure = SecurityException("synthetic permission failure")
        val thrown = assertThrows(SecurityException::class.java) {
            ReceiverWorkerLaunchGuard.start { throw failure }
        }
        assertSame(failure, thrown)
    }
}

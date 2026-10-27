package com.datanet.share.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SenderCleanupTest {
    @Test fun ordinaryFailuresDoNotSkipLaterResourceReleases() {
        val calls = mutableListOf<String>()
        SenderCleanup.run(
            { calls += "observer"; throw IllegalStateException("synthetic observer failure") },
            { calls += "proxy"; throw java.io.IOException("synthetic socket failure") },
            { calls += "wifi" },
            { calls += "cellular"; throw SecurityException("synthetic permission failure") },
            { calls += "wake-lock" },
            { calls += "foreground" }
        )
        assertEquals(listOf("observer", "proxy", "wifi", "cellular", "wake-lock", "foreground"), calls)
    }

    @Test fun interruptionDoesNotSkipCleanupAndPreservesInterruptFlag() {
        // Run on a dedicated thread so the test runner's interrupt state is untouched.
        var completed = false
        var interrupted = false
        val worker = Thread {
            SenderCleanup.run(
                { throw InterruptedException("synthetic stop interruption") },
                { completed = true }
            )
            interrupted = Thread.currentThread().isInterrupted
        }
        worker.start()
        worker.join(2_000)
        assertFalse("cleanup worker must finish", worker.isAlive)
        assertTrue(completed)
        assertTrue(interrupted)
    }

    @Test fun fatalErrorsAreNotSilentlyConvertedIntoSuccessfulCleanup() {
        var laterStepCalled = false
        try {
            SenderCleanup.run(
                { throw AssertionError("synthetic fatal error") },
                { laterStepCalled = true }
            )
            fail("fatal errors must propagate")
        } catch (_: AssertionError) {
            assertFalse(laterStepCalled)
        }
    }
}

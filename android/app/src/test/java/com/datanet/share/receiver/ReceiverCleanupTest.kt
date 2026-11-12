package com.datanet.share.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverCleanupTest {
    @Test fun failedReleaseDoesNotSkipDescriptorOrForegroundCleanup() {
        val completed = mutableListOf<String>()
        ReceiverCleanup.run(
            { completed += "watchdog"; throw IllegalStateException("release failed") },
            { completed += "descriptor" },
            { completed += "bridge"; throw IllegalArgumentException("flow failed") },
            { completed += "wake-lock" },
            { completed += "foreground" }
        )
        assertEquals(listOf("watchdog", "descriptor", "bridge", "wake-lock", "foreground"), completed)
    }

    @Test fun interruptionIsRestoredAfterRemainingCleanupSteps() {
        val completed = mutableListOf<String>()
        val thread = Thread.currentThread()
        val priorInterrupt = Thread.interrupted()
        try {
            ReceiverCleanup.run(
                { completed += "probe"; throw InterruptedException("interrupted release") },
                { completed += "descriptor" },
                { completed += "foreground" }
            )
            assertEquals(listOf("probe", "descriptor", "foreground"), completed)
            assertTrue(thread.isInterrupted)
        } finally {
            Thread.interrupted()
            if (priorInterrupt) thread.interrupt()
        }
    }

    @Test fun ordinaryReleaseFailureDoesNotInterruptCaller() {
        val priorInterrupt = Thread.interrupted()
        try {
            ReceiverCleanup.run({ throw IllegalStateException("failed") }, {})
            assertFalse(Thread.currentThread().isInterrupted)
        } finally {
            if (priorInterrupt) Thread.currentThread().interrupt()
        }
    }
}

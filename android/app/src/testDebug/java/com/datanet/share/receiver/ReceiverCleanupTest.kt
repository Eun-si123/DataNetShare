package com.datanet.share.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReceiverCleanupTest {
    @Test fun failuresDoNotSkipTunnelCloseAndForegroundRelease() {
        val released = mutableListOf<String>()
        ReceiverCleanup.run(
            { released += "watchdog"; throw SecurityException("synthetic interrupt denial") },
            { released += "probe"; throw java.io.IOException("synthetic socket close failure") },
            { released += "tun" },
            { released += "bridge"; throw IllegalStateException("synthetic flow close failure") },
            { released += "wake-lock"; throw IllegalStateException("synthetic wake-lock failure") },
            { released += "foreground" }
        )
        assertEquals(
            listOf("watchdog", "probe", "tun", "bridge", "wake-lock", "foreground"),
            released
        )
    }

    @Test fun interruptedCleanupFinishesAndRestoresInterruptStatus() {
        var finished = false
        var interrupted = false
        val worker = Thread {
            ReceiverCleanup.run(
                { throw InterruptedException("synthetic teardown interruption") },
                { finished = true }
            )
            interrupted = Thread.currentThread().isInterrupted
        }
        worker.start()
        worker.join(2_000)
        assertFalse("cleanup worker should finish", worker.isAlive)
        assertTrue(finished)
        assertTrue(interrupted)
    }

    @Test fun fatalErrorsRemainVisible() {
        var laterStepCalled = false
        try {
            ReceiverCleanup.run(
                { throw AssertionError("synthetic fatal teardown error") },
                { laterStepCalled = true }
            )
            fail("fatal errors must not be hidden")
        } catch (_: AssertionError) {
            assertFalse(laterStepCalled)
        }
    }
}

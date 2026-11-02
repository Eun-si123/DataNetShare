package com.datanet.share.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class SenderCleanupTest {
    @Test fun failedProxyAndNetworkReleasesDoNotSkipOtherOwnedResources() {
        val released = mutableListOf<String>()
        SenderCleanup.run(
            { released += "observer" },
            { released += "proxy"; throw IOException("synthetic private socket failure") },
            { released += "wifi group" },
            { released += "cellular"; throw IllegalStateException("synthetic callback failure") },
            { released += "wake lock" },
            { released += "foreground notification" }
        )
        assertEquals(listOf("observer", "proxy", "wifi group", "cellular",
            "wake lock", "foreground notification"), released)
    }

    @Test fun interruptedReleaseDoesNotSkipCleanupAndRestoresCancellation() {
        val result = AtomicReference<Pair<List<String>, Boolean>>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                val released = mutableListOf<String>()
                SenderCleanup.run(
                    { released += "proxy"; throw InterruptedException("synthetic cancellation") },
                    { released += "wifi group" },
                    { released += "cellular" },
                    { released += "wake lock" }
                )
                result.set(released to Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        worker.join(3_000)
        assertFalse("teardown worker must terminate", worker.isAlive)
        failure.get()?.let { throw AssertionError("teardown threw unexpectedly", it) }
        assertEquals(listOf("proxy", "wifi group", "cellular", "wake lock"), result.get().first)
        assertTrue("interrupt must be restored after releasing resources", result.get().second)
    }

    @Test fun fatalErrorsAreNotSilentlyConvertedIntoSuccessfulTeardown() {
        val fatal = AssertionError("synthetic fatal VM-level error")
        var afterFatal = false
        try {
            SenderCleanup.run(
                { throw fatal },
                { afterFatal = true }
            )
            fail("fatal errors must propagate")
        } catch (caught: AssertionError) {
            assertSame(fatal, caught)
        }
        assertFalse(afterFatal)
    }
}

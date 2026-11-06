package com.datanet.share.receiver

import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class TunPollRecoveryTest {
    @Test fun interruptedPollReturnsToCallerWithoutUnboundedRetry() {
        var calls = 0
        val result = TunPollRecovery.poll(
            operation = { calls++; throw InterruptedIOException() },
            interrupted = { it is InterruptedIOException }
        )
        assertNull(result)
        assertEquals(1, calls)
    }

    @Test fun readyAndTimeoutResultsPassThrough() {
        assertEquals(1, TunPollRecovery.poll({ 1 }, { false }))
        assertEquals(0, TunPollRecovery.poll({ 0 }, { false }))
    }

    @Test fun fatalPollErrorIsNotSwallowed() {
        try {
            TunPollRecovery.poll(
                operation = { throw IllegalStateException("bad TUN descriptor") },
                interrupted = { it is InterruptedIOException }
            )
            fail("Non-interruption poll errors must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("bad TUN descriptor", expected.message)
        }
    }

    private class SimulatedErrno(val code: Int) : Exception("synthetic poll errno")

    @Test fun onlyEintrIsRecoverableWhileOtherErrnosPropagate() {
        val eintr = SimulatedErrno(4)
        val eagain = SimulatedErrno(11)
        assertNull(TunPollRecovery.poll(
            operation = { throw eintr },
            interrupted = { it is SimulatedErrno && it.code == 4 }
        ))
        try {
            TunPollRecovery.poll(
                operation = { throw eagain },
                interrupted = { it is SimulatedErrno && it.code == 4 }
            )
            fail("Non-EINTR poll errors must reach VPN failure handling")
        } catch (caught: SimulatedErrno) {
            assertSame(eagain, caught)
        }
    }

    @Test fun fatalVmErrorsBypassTheRecoverableExceptionClassifier() {
        val fatal = AssertionError("synthetic fatal poll error")
        var classifierCalls = 0
        try {
            TunPollRecovery.poll(
                operation = { throw fatal },
                interrupted = { classifierCalls++; true }
            )
            fail("Fatal errors must not be classified as recoverable EINTR")
        } catch (caught: AssertionError) {
            assertSame(fatal, caught)
        }
        assertEquals(0, classifierCalls)
    }
}

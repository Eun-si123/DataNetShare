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
}

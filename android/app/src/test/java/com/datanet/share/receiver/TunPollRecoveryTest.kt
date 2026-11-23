package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TunPollRecoveryTest {
    @Test fun successfulPollReturnsReadyDescriptorCount() {
        assertEquals(2, TunPollRecovery.poll({ 2 }) { false })
    }

    @Test fun interruptedPollReturnsNullForCallerRetry() {
        val interrupted = IOException("EINTR")
        assertNull(TunPollRecovery.poll({ throw interrupted }) { it === interrupted })
    }

    @Test fun unrelatedPollFailureIsNotSuppressed() {
        val failure = IOException("EBADF")
        try {
            TunPollRecovery.poll({ throw failure }) { false }
            fail("Non-EINTR poll failure must propagate")
        } catch (caught: IOException) {
            assertSame(failure, caught)
        }
    }

    @Test fun classifierFailureIsNotSuppressed() {
        val classifierFailure = IllegalStateException("classifier failed")
        try {
            TunPollRecovery.poll({ throw IOException("poll failed") }) { throw classifierFailure }
            fail("Classifier failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(classifierFailure, caught)
        }
    }
}

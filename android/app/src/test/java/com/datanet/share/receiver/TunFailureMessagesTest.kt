package com.datanet.share.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunFailureMessagesTest {
    @Test fun readErrorDoesNotExposePlatformExceptionDetails() {
        val marker = "private-session-marker-for-test"
        val status = TunFailureMessages.read(IllegalStateException(marker))
        assertTrue(status.contains("packet loop"))
        assertTrue(status.contains("Reconnect"))
        assertFalse(status.contains(marker))
        assertFalse(status.contains("IllegalStateException"))
    }

    @Test fun writeErrorDoesNotExposePlatformExceptionDetails() {
        val marker = "private-network-marker-for-test"
        val status = TunFailureMessages.write(java.io.IOException(marker))
        assertTrue(status.contains("packet write"))
        assertTrue(status.contains("Reconnect"))
        assertFalse(status.contains(marker))
        assertFalse(status.contains("IOException"))
    }
}

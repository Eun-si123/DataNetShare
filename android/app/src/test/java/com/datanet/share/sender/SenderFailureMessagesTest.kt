package com.datanet.share.sender

import java.net.BindException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderFailureMessagesTest {
    private val sensitive = "PRIVATE_QR_OR_NETWORK_DETAIL"

    @Test fun startupNeverExposesExceptionDetails() {
        for (failure in listOf(
            SecurityException(sensitive),
            IllegalArgumentException(sensitive),
            IllegalStateException(sensitive)
        )) {
            assertFalse(SenderFailureMessages.startup(failure).contains(sensitive))
        }
    }

    @Test fun proxyFailureRedactsSocketDetailsAndPreservesUsefulCategory() {
        val binding = SenderFailureMessages.proxyStart(BindException(sensitive))
        assertTrue(binding.contains("already in use"))
        assertFalse(binding.contains(sensitive))
        assertFalse(SenderFailureMessages.proxyStart(SecurityException(sensitive)).contains(sensitive))
        assertFalse(SenderFailureMessages.proxyStart(IllegalStateException(sensitive)).contains(sensitive))
    }

    @Test fun listenerFailureIsSafeAndActionable() {
        assertTrue(SenderFailureMessages.LISTENER_STOPPED.contains("restart"))
        assertFalse(SenderFailureMessages.LISTENER_STOPPED.contains(sensitive))
    }
}

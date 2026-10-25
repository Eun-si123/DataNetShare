package com.datanet.share.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReceiverStartupFailureMessagesTest {
    private val marker = "private-network-and-credential-marker-for-test"

    @Test fun senderProbeFailureDoesNotExposeExceptionDetails() {
        val message = ReceiverStartupFailureMessages.senderCheck(IOException(marker))
        assertTrue(message.contains("Sender check failed"))
        assertTrue(message.contains("proxy password"))
        assertFalse(message.contains(marker))
        assertFalse(message.contains("IOException"))
    }

    @Test fun receiverStartFailureDoesNotExposeExceptionDetails() {
        val message = ReceiverStartupFailureMessages.receiverStart(IllegalStateException(marker))
        assertTrue(message.contains("Receiver could not start"))
        assertTrue(message.contains("VPN permission"))
        assertFalse(message.contains(marker))
        assertFalse(message.contains("IllegalStateException"))
    }

    @Test fun vpnTunnelStartFailureDoesNotExposeExceptionDetails() {
        val message = ReceiverStartupFailureMessages.tunnelStart(IOException(marker))
        assertTrue(message.contains("VPN could not start"))
        assertTrue(message.contains("local sender link"))
        assertFalse(message.contains(marker))
        assertFalse(message.contains("IOException"))
    }
}

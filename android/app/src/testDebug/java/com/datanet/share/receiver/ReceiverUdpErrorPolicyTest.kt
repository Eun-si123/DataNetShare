package com.datanet.share.receiver

import com.datanet.share.common.NetworkEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverUdpErrorPolicyTest {
    @Test fun callbackErrorsAreRelayFailuresNotSetupFailures() {
        assertEquals(NetworkEvent.UDP_RELAY,
            ReceiverUdpErrorPolicy.classify("UDP receive callback failed (IllegalStateException)"))
    }

    @Test fun existingFailureCategoriesStayStable() {
        assertEquals(NetworkEvent.UDP_FLOW_LIMIT,
            ReceiverUdpErrorPolicy.classify("UDP capacity limit reached"))
        assertEquals(NetworkEvent.UDP_QUEUE,
            ReceiverUdpErrorPolicy.classify("UDP queue full"))
        assertEquals(NetworkEvent.UDP_RELAY,
            ReceiverUdpErrorPolicy.classify("UDP relay read failed (SocketException)"))
        assertEquals(NetworkEvent.UDP_SETUP,
            ReceiverUdpErrorPolicy.classify("UDP proxy setup failed"))
    }
}

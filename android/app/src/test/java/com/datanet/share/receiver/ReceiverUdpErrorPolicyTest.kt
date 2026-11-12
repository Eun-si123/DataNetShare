package com.datanet.share.receiver

import com.datanet.share.common.NetworkEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverUdpErrorPolicyTest {
    @Test fun classifiesKnownUdpFailureStages() {
        val cases = mapOf(
            "Receiver UDP capacity reached (TCP 0, UDP 64, total budget 112)" to NetworkEvent.UDP_FLOW_LIMIT,
            "UDP packet queue full" to NetworkEvent.UDP_QUEUE,
            "UDP relay read failed (IOException)" to NetworkEvent.UDP_RELAY,
            "UDP receive callback failed (IOException)" to NetworkEvent.UDP_RELAY,
            "UDP packet exceeds VPN MTU" to NetworkEvent.UDP_SETUP,
            "UDP relay interrupted. Reconnect to resume." to NetworkEvent.UDP_SETUP,
            "UDP relay worker could not start. Reconnect to resume." to NetworkEvent.UDP_SETUP,
        )
        cases.forEach { (message, expected) ->
            assertEquals(message, expected, ReceiverUdpErrorPolicy.classify(message))
        }
    }

    @Test fun unknownFailuresKeepSetupFallback() {
        assertEquals(NetworkEvent.UDP_SETUP, ReceiverUdpErrorPolicy.classify("unrecognized UDP error"))
        assertEquals(NetworkEvent.UDP_SETUP, ReceiverUdpErrorPolicy.classify(""))
    }
}

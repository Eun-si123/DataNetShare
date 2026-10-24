package com.datanet.share.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrNetworkLossPolicyTest {
    @Test fun lossBeforeIpv4ProvisioningIsReported() {
        val pending = Any()
        assertTrue(QrNetworkLossPolicy.shouldMarkLost(pending, pending, null, null))
    }

    @Test fun lossAfterUnreachableProbeIsStillReported() {
        val observed = Any()
        // The verifier has finished unsuccessfully: no connected or verifying network.
        assertTrue(QrNetworkLossPolicy.shouldMarkLost(observed, observed, null, null))
    }

    @Test fun verifyingOrConnectedPeerLossIsReported() {
        val verifying = Any()
        val connected = Any()
        assertTrue(QrNetworkLossPolicy.shouldMarkLost(verifying, null, verifying, null))
        assertTrue(QrNetworkLossPolicy.shouldMarkLost(connected, null, null, connected))
    }

    private data class NetworkId(val id: Int)

    @Test fun equalNetworkIdsFromDifferentCallbackObjectsAreRecognized() {
        val observed = NetworkId(42)
        val callbackValue = NetworkId(42)
        assertTrue(observed !== callbackValue)
        assertTrue(QrNetworkLossPolicy.shouldMarkLost(callbackValue, observed, null, null))
        assertTrue(QrNetworkLossPolicy.shouldMarkLost(callbackValue, null, observed, null))
        assertFalse(QrNetworkLossPolicy.shouldMarkLost(NetworkId(43), observed, null, null))
    }

    @Test fun staleOrUnrelatedCandidateLossCannotDropActivePeer() {
        val active = Any()
        val candidate = Any()
        val stale = Any()
        assertFalse(QrNetworkLossPolicy.shouldMarkLost(candidate, candidate, null, active))
        assertFalse(QrNetworkLossPolicy.shouldMarkLost(candidate, candidate, active, null))
        assertFalse(QrNetworkLossPolicy.shouldMarkLost(stale, candidate, null, null))
        assertFalse(QrNetworkLossPolicy.shouldMarkLost(stale, null, null, active))
    }

    private enum class CallbackStatus {
        REQUESTING, CHECKING_SENDER, CONNECTED, SENDER_UNREACHABLE, LOST, UNAVAILABLE
    }

    @Test fun lateCallbacksCannotReviveTerminalRequest() {
        for (terminal in listOf(CallbackStatus.LOST, CallbackStatus.UNAVAILABLE)) {
            assertFalse(QrNetworkLossPolicy.acceptsNetworkUpdate(
                terminal, CallbackStatus.LOST, CallbackStatus.UNAVAILABLE))
        }
    }

    @Test fun activeAndRetryableRequestsStillAcceptNetworkUpdates() {
        for (active in listOf(
            CallbackStatus.REQUESTING, CallbackStatus.CHECKING_SENDER,
            CallbackStatus.CONNECTED, CallbackStatus.SENDER_UNREACHABLE
        )) {
            assertTrue(QrNetworkLossPolicy.acceptsNetworkUpdate(
                active, CallbackStatus.LOST, CallbackStatus.UNAVAILABLE))
        }
    }

}

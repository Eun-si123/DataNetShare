package com.datanet.share.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only link-selection policy tests; no Android Network or physical adapter is exercised. */
class InterfaceEnumeratorCandidateTest {
    private fun candidate(name: String, type: InterfaceEnumerator.InterfaceType) =
        InterfaceEnumerator.InterfaceInfo(name, name, "10.20.30.40", type)

    @Test fun privateTunnelAddressesAreNotEligibleForLocalSocksBinding() {
        // A private IPv4 address is not sufficient to identify a trusted local link.
        for (name in listOf("tun0", "wg0", "wireguard0", "WireGuard0", "ipsec0", "tailscale0", "v4-wlan0")) {
            assertFalse("Tunnel unexpectedly eligible: $name",
                InterfaceEnumerator.isLocalCandidate(candidate(name, InterfaceEnumerator.InterfaceType.OTHER)))
        }
    }

    @Test fun physicalAndUnfamiliarPrivateLinksRemainEligible() {
        assertTrue(InterfaceEnumerator.isLocalCandidate(candidate("wlan0", InterfaceEnumerator.InterfaceType.WIFI)))
        assertTrue(InterfaceEnumerator.isLocalCandidate(candidate("p2p0", InterfaceEnumerator.InterfaceType.WIFI_DIRECT)))
        assertTrue(InterfaceEnumerator.isLocalCandidate(candidate("usb0", InterfaceEnumerator.InterfaceType.USB_TETHERING)))
        assertTrue(InterfaceEnumerator.isLocalCandidate(candidate("eth0", InterfaceEnumerator.InterfaceType.OTHER)))
    }
}

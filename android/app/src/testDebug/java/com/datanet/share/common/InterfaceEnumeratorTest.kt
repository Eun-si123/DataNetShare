package com.datanet.share.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceEnumeratorTest {
    private fun candidate(name: String, type: InterfaceEnumerator.InterfaceType, ip: String = "192.168.49.1") =
        InterfaceEnumerator.InterfaceInfo(name, name, ip, type)

    @Test fun excludesKnownVirtualLinksEvenWithPrivateIpv4() {
        listOf("tun0", "tap0", "wg0", "ipsec0", "ppp0", "veth123", "dummy0",
            "tailscale0", "v4-wlan0", "V4-WLAN0", "TUN12").forEach { name ->
            assertFalse("Virtual interface offered for local sharing: $name",
                InterfaceEnumerator.isLocalCandidate(candidate(name, InterfaceEnumerator.InterfaceType.OTHER)))
        }
    }

    @Test fun retainsPhysicalAndUnfamiliarOemLocalLinks() {
        listOf(
            candidate("wlan0", InterfaceEnumerator.InterfaceType.WIFI),
            candidate("p2p0", InterfaceEnumerator.InterfaceType.WIFI_DIRECT),
            candidate("rndis0", InterfaceEnumerator.InterfaceType.USB_TETHERING),
            candidate("eth0", InterfaceEnumerator.InterfaceType.OTHER),
            candidate("ap0", InterfaceEnumerator.InterfaceType.OTHER)
        ).forEach { info ->
            assertTrue("Local link unexpectedly excluded: ${info.name}",
                InterfaceEnumerator.isLocalCandidate(info))
        }
    }

    @Test fun rejectsCellularAndPublicOtherAddresses() {
        assertFalse(InterfaceEnumerator.isLocalCandidate(candidate("rmnet0", InterfaceEnumerator.InterfaceType.CELLULAR)))
        assertFalse(InterfaceEnumerator.isLocalCandidate(candidate("eth0", InterfaceEnumerator.InterfaceType.OTHER, "8.8.8.8")))
    }
}

package com.datanet.share.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverLinkFallbackPolicyTest {
    @Test fun rejectsVirtualAndVpnInterfacesEvenIfTheyHavePrivateIpv4() {
        listOf(
            "tun0", "tap0", "wg0", "ipsec0", "ppp0", "veth1234",
            "dummy0", "tailscale0", "TUN12", "WG1",
            "v4-wlan0", "v4-rmnet_data0", "V4-ETH0",
            "ip6tnl0", "sit0", "gre0", "gretap0", "erspan0", "clat4"
        ).forEach { name ->
            assertFalse("Virtual interface must not carry SOCKS transport: $name",
                ReceiverLinkFallbackPolicy.allows(name))
        }
    }

    @Test fun keepsPhysicalAndUnfamiliarOemLocalLinkNamesEligible() {
        listOf("wlan0", "p2p0", "rndis0", "usb0", "ncm0", "eth0", "ap0", "swlan0", "green0", "sitewifi0")
            .forEach { name ->
                assertTrue("Local link unexpectedly excluded: $name",
                    ReceiverLinkFallbackPolicy.allows(name))
            }
    }
}

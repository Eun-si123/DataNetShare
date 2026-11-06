package com.datanet.share.receiver

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverLinkAddressPolicyTest {
    private fun ip(text: String) = InetAddress.getByName(text)
    private fun matches(local: String, peer: String, prefix: Int): Boolean =
        ReceiverLinkAddressPolicy.sameIpv4Prefix(ip(local), ip(peer), prefix)

    @Test fun matchingPrivateWifiSubnetAcceptsPeerButNotAdjacentSubnet() {
        assertTrue(matches("192.168.49.2", "192.168.49.1", 24))
        assertFalse(matches("192.168.49.2", "192.168.50.1", 24))
    }

    @Test fun hostAndPointToPointPrefixesDoNotOvermatch() {
        assertTrue(matches("10.0.0.1", "10.0.0.1", 32))
        assertFalse(matches("10.0.0.1", "10.0.0.2", 32))
        assertTrue(matches("10.0.0.0", "10.0.0.1", 31))
        assertFalse(matches("10.0.0.0", "10.0.0.2", 31))
    }

    @Test fun signBitAndInvalidPrefixLengthsAreHandledSafely() {
        assertTrue(matches("10.0.0.1", "100.64.0.1", 1))
        assertFalse(matches("10.0.0.1", "128.0.0.1", 1))
        for (prefix in listOf(-1, 0, 33, 64)) {
            assertFalse(matches("192.168.49.1", "192.168.49.1", prefix))
        }
    }

    @Test fun thirtyBitBoundaryDoesNotAcceptAdjacentSubnet() {
        assertTrue(matches("192.168.49.5", "192.168.49.6", 30))
        assertFalse(matches("192.168.49.5", "192.168.49.9", 30))
    }

    @Test fun ipv6NeverQualifiesAsLocalIpv4SenderLink() {
        assertFalse(matches("fe80::1", "192.168.49.1", 24))
        assertFalse(matches("192.168.49.1", "fe80::1", 24))
    }
}

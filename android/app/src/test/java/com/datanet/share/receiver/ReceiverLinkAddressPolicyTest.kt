package com.datanet.share.receiver

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverLinkAddressPolicyTest {
    private fun ip(value: String) = InetAddress.getByName(value)

    @Test fun matchesOnlyAddressesInsideIpv4Prefix() {
        assertTrue(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("192.168.42.1"), ip("192.168.42.254"), 24))
        assertFalse(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("192.168.42.1"), ip("192.168.43.1"), 24))
        assertTrue(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("10.20.30.40"), ip("10.20.30.41"), 31))
        assertFalse(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("10.20.30.40"), ip("10.20.30.42"), 31))
    }

    @Test fun exactHostPrefixOnlyMatchesSameIpv4() {
        assertTrue(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("172.16.1.8"), ip("172.16.1.8"), 32))
        assertFalse(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("172.16.1.8"), ip("172.16.1.9"), 32))
    }

    @Test fun rejectsInvalidPrefixesAndIpv6WithoutThrowing() {
        for (prefix in listOf(-1, 0, 33, 128)) {
            assertFalse(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("192.168.1.1"), ip("192.168.1.2"), prefix))
        }
        assertFalse(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("::1"), ip("::1"), 32))
        assertFalse(ReceiverLinkAddressPolicy.sameIpv4Prefix(ip("192.168.1.1"), ip("::1"), 24))
    }
}

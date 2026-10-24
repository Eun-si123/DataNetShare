package com.datanet.share.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {
    @Test fun acceptsUnicastSenderAddressesOnPrivateAndLocalLinks() {
        for (host in listOf(
            "1.0.0.1", "10.0.0.1", "100.64.0.1", "126.255.255.254",
            "128.0.0.1", "169.254.42.1", "172.16.0.1", "192.168.49.1", "223.0.0.1"
        )) assertTrue("Valid unicast address rejected: $host", Endpoint.isIpv4(host))
    }

    @Test fun rejectsThisNetworkLoopbackMulticastAndReservedEndpoints() {
        for (host in listOf(
            "0.0.0.0", "0.0.0.1", "127.0.0.1", "127.255.255.254",
            "224.0.0.1", "239.255.255.255", "240.0.0.1",
            "255.255.255.254", "255.255.255.255"
        )) assertFalse("Non-unicast endpoint accepted: $host", Endpoint.isIpv4(host))
    }

    @Test fun rejectsMalformedOrAmbiguousIpv4Text() {
        for (host in listOf(
            "", " 192.168.1.1", "1.2.3", "1.2.3.4.5", "1..2.3",
            "01.2.3.4", "1.02.3.4", "256.1.2.3", "1.2.3.-1",
            "1.2.3.4\n", "１２.2.3.4", "1.2.3.99999999999999999"
        )) assertFalse("Malformed endpoint accepted: $host", Endpoint.isIpv4(host))
    }
}

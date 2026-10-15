package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class UdpMappingPolicyTest {
    private val ipA = byteArrayOf(10, 0, 0, 1)
    private val ipB = byteArrayOf(10, 0, 0, 2)

    @Test fun sameClientCanReuseMapping() {
        assertTrue(UdpMappingPolicy.isSameClient(ipA, 55001, ipA.clone(), 55001))
    }
    @Test fun differentSourcePortCannotOverwriteMapping() {
        assertFalse(UdpMappingPolicy.isSameClient(ipA, 55001, ipA, 55002))
    }
    @Test fun differentSourceIpCannotOverwriteMapping() {
        assertFalse(UdpMappingPolicy.isSameClient(ipA, 55001, ipB, 55001))
    }
}

package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test

class TcpCapacityPolicyTest {
    @Test fun tcpBorrowsUnusedUdpHeadroomButReservesControlClients() {
        assertTrue(TcpCapacityPolicy.allowTcp(80, 4))
        assertTrue(TcpCapacityPolicy.allowTcp(111, 4))
        assertFalse(TcpCapacityPolicy.allowTcp(112, 0))
        assertFalse(TcpCapacityPolicy.allowTcp(90, 30))
        assertTrue(TcpCapacityPolicy.allowTcp(89, 30))
    }
    @Test fun udpAdmissionRespectsSharedBudget() {
        assertTrue(TcpCapacityPolicy.allowUdp(80, 39))
        assertFalse(TcpCapacityPolicy.allowUdp(81, 39))
        assertFalse(TcpCapacityPolicy.allowUdp(0, 40))
        assertTrue(TcpCapacityPolicy.allowUdp(20, 39))
    }
    @Test fun onlyOldIdleFlowsBecomeReclaimable() {
        assertFalse(TcpCapacityPolicy.evictIdle(5_000_000_000L, false))
        assertTrue(TcpCapacityPolicy.evictIdle(7_000_000_000L, false))
        assertFalse(TcpCapacityPolicy.evictIdle(10_000_000_000L, true))
        assertTrue(TcpCapacityPolicy.evictIdle(21_000_000_000L, true))
    }
}

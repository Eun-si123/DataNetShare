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

    @Test fun admissionMatrixPreservesEightControlSlots() {
        // Verify both rejection at capacity and admission while room remains.
        // A policy that rejects every new flow must fail this matrix too.
        assertEquals(120, TcpCapacityPolicy.TOTAL_BUDGET)
        for (tcp in 0..TcpCapacityPolicy.TOTAL_BUDGET) {
            for (udp in 0..TcpCapacityPolicy.TOTAL_BUDGET) {
                val room = tcp + udp < TcpCapacityPolicy.TOTAL_BUDGET
                assertEquals("TCP: tcp=$tcp udp=$udp",
                    tcp < TcpCapacityPolicy.MAX_TCP && room,
                    TcpCapacityPolicy.allowTcp(tcp, udp))
                assertEquals("UDP: tcp=$tcp udp=$udp",
                    udp < TcpCapacityPolicy.MAX_UDP && room,
                    TcpCapacityPolicy.allowUdp(tcp, udp))
            }
        }
    }

    @Test fun idleReclaimBeginsAtExactThresholdNotOneNanosecondEarly() {
        val halfOpen = TcpCapacityPolicy.HALF_OPEN_RECLAIM_NANOS
        val established = TcpCapacityPolicy.IDLE_RECLAIM_NANOS
        assertFalse(TcpCapacityPolicy.evictIdle(halfOpen - 1, false))
        assertTrue(TcpCapacityPolicy.evictIdle(halfOpen, false))
        assertFalse(TcpCapacityPolicy.evictIdle(established - 1, true))
        assertTrue(TcpCapacityPolicy.evictIdle(established, true))
    }
    @Test fun invalidOrOverflowedCountsNeverAdmitAnotherFlow() {
        val invalidCounts = listOf(
            -1 to 0, 0 to -1, Int.MIN_VALUE to Int.MIN_VALUE,
            1 to Int.MAX_VALUE, Int.MAX_VALUE to 1,
            Int.MAX_VALUE to Int.MAX_VALUE
        )
        for ((tcp, udp) in invalidCounts) {
            assertFalse("TCP must reject invalid counts tcp=$tcp udp=$udp", TcpCapacityPolicy.allowTcp(tcp, udp))
            assertFalse("UDP must reject invalid counts tcp=$tcp udp=$udp", TcpCapacityPolicy.allowUdp(tcp, udp))
        }
    }
}

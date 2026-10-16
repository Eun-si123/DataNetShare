package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test

class UdpFlowPolicyTest {
    @Test fun limitLeavesSenderSlotsForHealthProbes() {
        assertEquals(40, UdpFlowPolicy.MAX_FLOWS)
        assertTrue(80 + UdpFlowPolicy.MAX_FLOWS < 128)
    }
    @Test fun onlyReclaimSubstantiallyIdleFlowsWhenFull() {
        val age = UdpFlowPolicy.EVICT_IDLE_NANOS
        assertFalse(UdpFlowPolicy.shouldEvictAtCapacity(39, age + 1))
        assertFalse(UdpFlowPolicy.shouldEvictAtCapacity(40, age - 1))
        assertTrue(UdpFlowPolicy.shouldEvictAtCapacity(40, age))
        assertTrue(UdpFlowPolicy.shouldEvictAtCapacity(41, age + 1))
    }
}

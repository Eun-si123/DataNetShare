package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test

class UdpFlowPolicyTest {
    @Test fun limitLeavesSenderSlotsForHealthProbes() {
        assertEquals(40, UdpFlowPolicy.MAX_FLOWS)
        assertTrue(80 + UdpFlowPolicy.MAX_FLOWS < 128)
    }
    @Test fun onlyReclaimSubstantiallyIdleFlowsWhenFull() {
        assertFalse(UdpFlowPolicy.shouldEvictAtCapacity(39, true))
        assertFalse(UdpFlowPolicy.shouldEvictAtCapacity(40, false))
        assertTrue(UdpFlowPolicy.shouldEvictAtCapacity(40, true))
        assertTrue(UdpFlowPolicy.shouldEvictAtCapacity(41, true))
    }
}

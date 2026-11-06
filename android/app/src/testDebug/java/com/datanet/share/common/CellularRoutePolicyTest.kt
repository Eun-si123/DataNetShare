package com.datanet.share.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CellularRoutePolicyTest {
    @Test fun invalidatingBestCellularLinkFallsBackToPreviouslyEligibleLink() {
        val candidates = CellularRouteCandidates<String>()
        candidates.observe("cellular-A", true)
        assertEquals("cellular-A", candidates.selected())
        candidates.observe("cellular-B", true)
        assertEquals("cellular-B", candidates.selected())
        candidates.observe("cellular-B", false) // VPN or no Internet capability
        assertEquals("cellular-A", candidates.selected())
        candidates.observe("cellular-B", true)
        assertEquals("cellular-B", candidates.selected())
        candidates.observe("cellular-A", false)
        assertEquals("cellular-B", candidates.selected())
        candidates.observe("cellular-B", false)
        assertEquals(null, candidates.selected())
    }

    @Test fun candidateStateDoesNotSurviveTrackerStop() {
        val candidates = CellularRouteCandidates<Int>()
        candidates.observe(1, true)
        candidates.observe(2, true)
        candidates.clear()
        assertEquals(null, candidates.selected())
        candidates.observe(3, true)
        assertEquals(3, candidates.selected())
    }

    @Test fun rejectsVpnAndNonCellularNetworksEvenWithInternetCapability() {
        for (cellular in listOf(false, true)) {
            for (vpn in listOf(false, true)) {
                for (internet in listOf(false, true)) {
                    assertEquals(
                        "cellular=$cellular vpn=$vpn internet=$internet",
                        cellular && !vpn && internet,
                        CellularRoutePolicy.eligible(cellular, vpn, internet)
                    )
                }
            }
        }
    }
}

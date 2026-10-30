package com.datanet.share.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CellularRoutePolicyTest {
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

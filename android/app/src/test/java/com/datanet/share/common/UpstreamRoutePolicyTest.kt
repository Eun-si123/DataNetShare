package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class UpstreamRoutePolicyTest {
    private val CELL = UpstreamRoutePolicy.Route.CELLULAR
    private val WIFI = UpstreamRoutePolicy.Route.WIFI

    @Test fun cellularReturnsToFirstPlaceWhenValidatedAgain() {
        val old = UpstreamRoutePolicy.order(true, true, true, true)
        val failed = UpstreamRoutePolicy.order(true, false, true, true)
        val recovered = UpstreamRoutePolicy.order(true, true, true, true)
        assertEquals(listOf(CELL, WIFI), old)
        assertEquals(listOf(WIFI, CELL), failed)
        assertEquals(listOf(CELL, WIFI), recovered)
    }

    @Test fun wifiFallbackIsExplicit() {
        assertEquals(listOf(CELL), UpstreamRoutePolicy.order(true, false, true, false))
        assertEquals(emptyList<UpstreamRoutePolicy.Route>(), UpstreamRoutePolicy.order(false, false, true, false))
        assertEquals(listOf(WIFI), UpstreamRoutePolicy.order(false, false, true, true))
    }

    @Test fun noInternetWifiDoesNotQualify() {
        assertEquals(listOf(CELL), UpstreamRoutePolicy.order(true, false, false, true))
        assertEquals(emptyList<UpstreamRoutePolicy.Route>(), UpstreamRoutePolicy.order(false, false, false, true))
    }
}

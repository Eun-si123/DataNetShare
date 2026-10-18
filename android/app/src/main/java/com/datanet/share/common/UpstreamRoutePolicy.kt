package com.datanet.share.common

/** Pure selection policy; Android Network objects are resolved separately at runtime. */
object UpstreamRoutePolicy {
    enum class Route { CELLULAR, WIFI }

    fun order(cellularAvailable: Boolean, cellularValidated: Boolean,
              validatedWifiAvailable: Boolean, wifiFallbackEnabled: Boolean): List<Route> {
        if (!wifiFallbackEnabled) return if (cellularAvailable) listOf(Route.CELLULAR) else emptyList()
        return if (cellularAvailable && cellularValidated) {
            listOf(Route.CELLULAR) + if (validatedWifiAvailable) listOf(Route.WIFI) else emptyList()
        } else {
            (if (validatedWifiAvailable) listOf(Route.WIFI) else emptyList()) +
                if (cellularAvailable) listOf(Route.CELLULAR) else emptyList()
        }
    }
}

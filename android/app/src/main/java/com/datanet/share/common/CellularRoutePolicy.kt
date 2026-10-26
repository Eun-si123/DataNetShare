package com.datanet.share.common

/** Only an actual Internet-capable cellular link is eligible for sender egress. */
internal object CellularRoutePolicy {
    fun eligible(cellular: Boolean, vpn: Boolean, internet: Boolean): Boolean =
        cellular && !vpn && internet
}

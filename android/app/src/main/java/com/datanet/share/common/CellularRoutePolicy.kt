package com.datanet.share.common

/** Only an actual Internet-capable cellular link is eligible for sender egress. */
internal object CellularRoutePolicy {
    fun eligible(cellular: Boolean, vpn: Boolean, internet: Boolean): Boolean =
        cellular && !vpn && internet
}

/** Maintain eligible links observed by initial scan and network callbacks.
 * The newest eligible callback wins, but the previous eligible link remains
 * available if that candidate becomes ineligible or disappears.
 * Access is serialized by CellularNetworkTracker's monitor.
 */
internal class CellularRouteCandidates<T : Any> {
    private val candidates = linkedSetOf<T>()

    fun observe(link: T, eligible: Boolean) {
        candidates.remove(link)
        if (eligible) candidates.add(link)
    }

    fun selected(): T? = candidates.lastOrNull()
    fun clear() = candidates.clear()
}

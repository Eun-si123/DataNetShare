package com.datanet.share.common

/**
 * A requested Wi-Fi Network can be lost before Android reports IPv4 properties.
 * Do not confuse a stale candidate disappearing with the connected/verifying peer.
 *
 * Android Network equality identifies a network ID even if callbacks use different objects.
 */
internal object QrNetworkLossPolicy {

    /**
     * LOST and UNAVAILABLE finish this QR request. Late Android callbacks must
     * not restart readiness checks for a request that already failed.
     */
    fun <T : Any> acceptsNetworkUpdate(status: T, lost: T, unavailable: T): Boolean =
        status != lost && status != unavailable

    fun <T : Any> shouldMarkLost(
        lost: T,
        observed: T?,
        verifying: T?,
        connected: T?
    ): Boolean =
        lost == verifying || lost == connected ||
            (lost == observed && verifying == null && connected == null)
}

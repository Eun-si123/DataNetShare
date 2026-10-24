package com.datanet.share.common

/**
 * A requested Wi-Fi Network can be lost before Android reports IPv4 properties.
 * Do not confuse a stale candidate disappearing with the connected/verifying peer.
 *
 * Identity matters: this is about one Android Network callback, not a subnet.
 */
internal object QrNetworkLossPolicy {
    fun <T : Any> shouldMarkLost(
        lost: T,
        observed: T?,
        verifying: T?,
        connected: T?
    ): Boolean =
        lost === verifying || lost === connected ||
            (lost === observed && verifying == null && connected == null)
}

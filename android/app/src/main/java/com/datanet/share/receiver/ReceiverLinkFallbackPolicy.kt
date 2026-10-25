package com.datanet.share.receiver

import java.util.Locale

/**
 * Network.bindSocket() is preferred. If Android exposes only a raw interface,
 * never choose a known VPN/tunnel interface as the local SOCKS transport.
 * Such an interface can share a private IPv4 prefix with the sender while
 * still routing the protected socket back into a VPN.
 *
 * Keep unfamiliar OEM local-link names eligible; this is a narrow safeguard,
 * not a replacement for Android's network selection.
 */
internal object ReceiverLinkFallbackPolicy {
    private val virtualPrefixes = listOf(
        "tun", "tap", "wg", "ipsec", "ppp", "veth", "dummy", "tailscale",
        "v4-" // Android 464XLAT stacked interface (AOSP Nat464Xlat.CLAT_PREFIX).
    )

    fun allows(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return virtualPrefixes.none { normalized.startsWith(it) }
    }
}

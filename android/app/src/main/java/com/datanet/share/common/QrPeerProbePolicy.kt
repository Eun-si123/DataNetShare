package com.datanet.share.common

/** Bounds local-only QR Wi-Fi readiness verification before requesting VPN. */
object QrPeerProbePolicy {
    const val MAX_ATTEMPTS = 5
    const val CONNECT_TIMEOUT_MS = 2_000
    const val DELAY_MS = 1_000L
}

package com.datanet.share.common

import java.io.IOException

/** Bounds local-only QR Wi-Fi readiness verification before requesting VPN. */
object QrPeerProbePolicy {
    const val MAX_ATTEMPTS = 5
    const val CONNECT_TIMEOUT_MS = 2_000
    const val DELAY_MS = 1_000L

    /**
     * A QR local-link probe is advisory; an Android socket-factory failure
     * must finish the check rather than strand CHECKING_SENDER forever.
     * Only I/O failures are retried; runtime/platform failures fail closed.
     */
    fun reachable(connect: () -> Unit, pause: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) return false
            try {
                connect()
                return true
            } catch (_: IOException) {
                if (attempt == MAX_ATTEMPTS) return false
                try {
                    pause(DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            } catch (_: RuntimeException) {
                // SecurityException/IllegalStateException can occur before connect().
                // Never surface exception details (potential endpoint information).
                return false
            }
        }
        return false
    }
}

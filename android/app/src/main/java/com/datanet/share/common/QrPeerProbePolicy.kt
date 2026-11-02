package com.datanet.share.common

import java.io.IOException

/** Bounds local-only QR Wi-Fi readiness verification before requesting VPN. */
object QrPeerProbePolicy {
    const val MAX_ATTEMPTS = 5
    const val CONNECT_TIMEOUT_MS = 2_000
    const val DELAY_MS = 1_000L

    /** Platform permission/runtime failures must not strand QR status in CHECKING_SENDER. */
    fun reachable(
        connect: () -> Unit,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        delay: (Long) -> Unit = { Thread.sleep(it) }
    ): Boolean {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (cancelled()) return false
            try {
                connect()
                return true
            } catch (_: IOException) {
                if (attempt == MAX_ATTEMPTS) return false
                try { delay(DELAY_MS) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            } catch (_: RuntimeException) {
                // Android can reject socket creation/connect without throwing IOException.
                // Fail closed instead of leaving the readiness worker dead and status stuck.
                return false
            }
        }
        return false
    }
}

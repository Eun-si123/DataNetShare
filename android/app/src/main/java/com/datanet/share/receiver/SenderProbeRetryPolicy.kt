package com.datanet.share.receiver

import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * A freshly authorized local-only Wi-Fi interface may have IPv4 before
 * the sender can actually be reached. Retry *route establishment* briefly,
 * but never retry bad proxy passwords or permission/protection failures.
 */
internal object SenderProbeRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val CONNECT_TIMEOUT_MS = 4_000
    const val BETWEEN_ATTEMPTS_MS = 900L

    fun routeError(error: Throwable): Boolean {
        if (error !is SocketException) return false
        // These are OS transport errors, not SOCKS authentication errors.
        val message = error.message.orEmpty()
        return listOf("EHOSTUNREACH", "ENETUNREACH", "No route to host",
            "Network is unreachable", "EHOSTDOWN", "Host is down",
            "Host is unreachable").any {
            message.contains(it, ignoreCase = true)
        }
    }

    fun shouldRetry(error: Throwable, attempt: Int, qrNetworkActive: Boolean): Boolean =
        qrNetworkActive && attempt in 1 until MAX_ATTEMPTS && routeError(error)
}

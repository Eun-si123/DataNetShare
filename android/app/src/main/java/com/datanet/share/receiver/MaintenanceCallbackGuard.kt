package com.datanet.share.receiver

/**
 * Optional telemetry must not terminate the receiver's TCP retransmission,
 * idle-expiry and congestion maintenance loop. Do not log callback exception
 * messages: observers may include network or session details.
 *
 * Only ordinary callback exceptions are isolated; VM errors are not hidden.
 */
internal object MaintenanceCallbackGuard {
    inline fun run(callback: () -> Unit) {
        try {
            callback()
        } catch (failure: Exception) {
            // InterruptedException clears the thread's cancellation flag.
            // Preserve it so the next maintenance sleep can stop promptly.
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            // Other observer failures are best-effort; keep maintenance alive.
        }
    }

    /**
     * A broken TCP maintenance tick is not optional telemetry. Report it once
     * and stop the maintenance loop rather than leaving a live but stalled VPN.
     * The caller owns service teardown; no exception details are exposed.
     */
    inline fun runCriticalTick(tick: () -> Unit, onFailure: () -> Unit): Boolean =
        try {
            tick()
            true
        } catch (failure: Exception) {
            try {
                onFailure()
            } finally {
                // Fail closed as before, but never erase an interrupt signal.
                if (failure is InterruptedException) Thread.currentThread().interrupt()
            }
            false
        }
}

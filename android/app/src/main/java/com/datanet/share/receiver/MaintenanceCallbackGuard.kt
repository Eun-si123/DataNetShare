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
        } catch (_: Exception) {
            // Best-effort reporting only; keep the maintenance loop alive.
        }
    }
}

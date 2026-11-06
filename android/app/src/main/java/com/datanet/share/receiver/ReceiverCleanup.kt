package com.datanet.share.receiver

/**
 * Best-effort VPN service teardown. An ordinary resource-release failure must
 * not skip closing the tunnel or relinquishing the foreground service.
 * Do not include exception messages in diagnostics: they may contain endpoints.
 */
internal object ReceiverCleanup {
    fun run(vararg steps: () -> Unit) {
        var interrupted = false
        try {
            for (step in steps) {
                try {
                    step()
                } catch (error: Exception) {
                    if (error is InterruptedException) interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

package com.datanet.share.receiver

/**
 * poll(2) can return EINTR when a signal arrives. This is not an invalid TUN
 * descriptor. Let the caller continue its bounded loop (read) or drop only
 * the current packet (write). All other errors remain fatal.
 *
 * The Android caller classifies ErrnoException/EINTR; this helper is pure JVM.
 */
internal object TunPollRecovery {
    inline fun poll(operation: () -> Int, interrupted: (Exception) -> Boolean): Int? =
        try {
            operation()
        } catch (error: Exception) {
            if (interrupted(error)) null else throw error
        }
}

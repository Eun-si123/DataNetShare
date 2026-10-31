package com.datanet.share.receiver

/**
 * Native thread creation can fail with OutOfMemoryError even when Java heap
 * space remains available (for example, at the OS task/thread limit).
 * Callers must stop and release any already-owned service resources when false.
 * Ordinary exceptions still use the existing startup failure path.
 */
internal object ReceiverWorkerLaunchGuard {
    inline fun start(launch: () -> Unit): Boolean = try {
        launch()
        true
    } catch (_: OutOfMemoryError) {
        false
    }
}

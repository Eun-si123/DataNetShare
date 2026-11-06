package com.datanet.share.receiver

/**
 * Publish a new flow before checking Stop again. Stop can close published flows;
 * a flow whose creation overlapped an already-completed Stop must close itself.
 * Callers start socket workers only after admission succeeds.
 */
internal inline fun <T : Any> admitFlow(
    isRunning: () -> Boolean,
    create: () -> T,
    register: (T) -> Unit,
    close: (T) -> Unit
): T? {
    if (!isRunning()) return null
    val flow = create()
    try {
        register(flow)
        if (isRunning()) return flow
    } catch (failure: Throwable) {
        // Registration may have partially published the flow before failing.
        // Always release it; preserve the original failure if cleanup also fails.
        try {
            close(flow)
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
    close(flow)
    return null
}

/**
 * Admission publishes a flow before its worker is launched. If Thread.start()
 * fails, the worker's finally block cannot release the published flow.
 * Release it here; native thread exhaustion must also fail the bridge closed.
 * Other launch failures retain their original type and reach the TUN reader's
 * existing failure handler. Worker run loops remain outside this guard.
 */
internal inline fun <T : Any> launchAdmittedFlow(
    flow: T,
    launch: () -> Unit,
    close: (T) -> Unit,
    onThreadExhausted: () -> Unit
): Boolean {
    try {
        launch()
        return true
    } catch (failure: Throwable) {
        var cleaned = true
        try {
            close(flow)
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
            cleaned = false
        }
        if (failure is OutOfMemoryError) {
            // Even partial teardown must not hide global thread exhaustion.
            // Preserve the original error if failure reporting itself fails.
            try { onThreadExhausted() }
            catch (reportFailure: Throwable) {
                if (reportFailure !== failure) failure.addSuppressed(reportFailure)
                throw failure
            }
            if (cleaned) return false
        }
        throw failure
    }
}

/** A native-thread launch failure is a bridge-wide failure, even when it began inside a UDP association. */
internal class NativeWorkerFailureGate {
    private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reportOnce(stop: () -> Unit, report: () -> Unit) {
        if (!reported.compareAndSet(false, true)) return
        try { stop() } finally { report() }
    }
}

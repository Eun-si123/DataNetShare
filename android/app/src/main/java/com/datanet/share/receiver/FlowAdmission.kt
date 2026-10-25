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

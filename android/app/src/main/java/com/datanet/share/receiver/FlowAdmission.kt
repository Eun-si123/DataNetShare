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
    register(flow)
    if (!isRunning()) {
        close(flow)
        return null
    }
    return flow
}

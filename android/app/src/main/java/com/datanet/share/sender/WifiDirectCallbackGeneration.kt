package com.datanet.share.sender

/**
 * Generation gate for asynchronous Wi-Fi Direct callbacks, timers and broadcasts.
 * Callers run on the Android main looper. Starting after Stop gets a fresh token;
 * replies from the previous channel may never mutate the next session.
 *
 * This does not establish P2P group ownership: Android createGroup.onSuccess()
 * only acknowledges initiation, not that a group has formed or was removed.
 */
internal class WifiDirectCallbackGeneration {
    var generation: Int = 0
        private set
    var running: Boolean = false
        private set

    fun start(): Int {
        check(!running) { "Wi-Fi Direct session is already active" }
        running = true
        return ++generation
    }

    fun stop() {
        running = false
        ++generation
    }

    fun accepts(token: Int): Boolean = running && generation == token
}

/** Shared delegate for the real WifiP2pManager.ActionListener and host fakes.
 * Keep the generation check *inside* the listener invocation path so the
 * listener cannot accidentally revive a Stop/restarted session.
 */
internal class WifiDirectCreateCallbacks(
    private val generations: WifiDirectCallbackGeneration,
    private val token: Int,
    private val onAccepted: () -> Unit,
    private val onRejected: (Int) -> Unit
) {
    fun onSuccess() {
        if (generations.accepts(token)) onAccepted()
    }

    fun onFailure(reason: Int) {
        if (generations.accepts(token)) onRejected(reason)
    }
}

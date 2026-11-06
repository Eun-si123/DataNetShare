package com.datanet.share.sender

/** Serializes a session's live UI posts with its terminal clear.
 * The caller invalidates the session token before clearing; callbacks then
 * cannot re-publish stale peer identifiers or counters after the clear.
 */
internal class SenderSessionPublicationGate {
    private val lock = Any()

    fun publishIfCurrent(expectedToken: Int, currentToken: () -> Int, publish: () -> Unit) {
        synchronized(lock) {
            if (currentToken() == expectedToken) publish()
        }
    }

    fun clearAfterTeardown(clear: () -> Unit) {
        synchronized(lock) { clear() }
    }
}

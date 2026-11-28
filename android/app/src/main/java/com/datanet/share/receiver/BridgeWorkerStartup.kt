package com.datanet.share.receiver

/** Roll back an already-started TUN worker if the next worker cannot launch. */
internal object BridgeWorkerStartup {
    inline fun launch(first: () -> Unit, second: () -> Unit, rollback: () -> Unit) {
        try {
            first()
            second()
        } catch (failure: Throwable) {
            try { rollback() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }
}

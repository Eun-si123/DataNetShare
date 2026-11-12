package com.datanet.share.receiver

/** Finish both flow-map cleanup paths even if one flow's close callback fails. */
internal object FlowStopCleanup {
    /** A cleanup failure must not suppress the original TUN failure notification. */
    fun reportAfterCleanup(cleanup: () -> Unit, report: () -> Unit) {
        try { cleanup() } finally { report() }
    }

    fun <T, U> closeAll(
        tcp: Iterable<T>, udp: Iterable<U>,
        closeTcp: (T) -> Unit, closeUdp: (U) -> Unit,
        clearTcp: () -> Unit, clearUdp: () -> Unit
    ) {
        var failure: Throwable? = null
        fun record(error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) failure!!.addSuppressed(error)
        }
        try {
            tcp.forEach { try { closeTcp(it) } catch (error: Throwable) { record(error) } }
        } finally {
            try { clearTcp() } catch (error: Throwable) { record(error) }
            try {
                udp.forEach { try { closeUdp(it) } catch (error: Throwable) { record(error) } }
            } finally {
                try { clearUdp() } catch (error: Throwable) { record(error) }
            }
        }
        failure?.let { throw it }
    }
}

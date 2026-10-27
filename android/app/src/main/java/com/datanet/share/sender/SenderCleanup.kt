package com.datanet.share.sender

/** Best-effort service teardown: ordinary close failures cannot skip later releases. */
internal object SenderCleanup {
    fun run(vararg steps: () -> Unit) {
        var interrupted = false
        try {
            steps.forEach { step ->
                try {
                    step()
                } catch (e: Exception) {
                    // Teardown is already in progress. Never surface exception
                    // text that might contain private network details.
                    if (e is InterruptedException) interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

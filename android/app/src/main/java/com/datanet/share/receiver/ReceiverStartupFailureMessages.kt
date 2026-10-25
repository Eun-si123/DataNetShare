package com.datanet.share.receiver

/**
 * Receiver startup and sender-probe failures appear in the session status.
 * Platform and socket exception messages can contain private addresses,
 * paths or credentials. Preserve a useful stage-specific hint, never the
 * untrusted exception text or class name.
 */
internal object ReceiverStartupFailureMessages {
    @Suppress("UNUSED_PARAMETER")
    fun senderCheck(cause: Exception): String =
        "Sender check failed. Check the local link, sender address and proxy password, then retry."

    @Suppress("UNUSED_PARAMETER")
    fun receiverStart(cause: Exception): String =
        "Receiver could not start. Check VPN permission and sender settings, then retry."

    @Suppress("UNUSED_PARAMETER")
    fun tunnelStart(cause: Exception): String =
        "VPN could not start. Check VPN permission and local sender link, then retry."
}

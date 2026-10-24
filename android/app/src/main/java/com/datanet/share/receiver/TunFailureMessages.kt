package com.datanet.share.receiver

/**
 * The TUN read/write errors are surfaced in the receiver's status and may be
 * copied into diagnostics. Platform exception messages can contain endpoint,
 * file or network details, so deliberately ignore the original exception.
 *
 * Keep operation context for troubleshooting without leaking its contents.
 */
internal object TunFailureMessages {
    @Suppress("UNUSED_PARAMETER")
    fun read(cause: Exception): String =
        "VPN packet loop stopped unexpectedly. Reconnect to resume."

    @Suppress("UNUSED_PARAMETER")
    fun write(cause: Exception): String =
        "VPN packet write failed. Reconnect to resume."
}

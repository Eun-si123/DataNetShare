package com.datanet.share.receiver

/**
 * Prepare a socket on the selected local link *before* asking VpnService.protect().
 *
 * Android may allocate a Socket's file descriptor lazily. Both Network.bindSocket()
 * and Socket.bind() cause the descriptor to exist while it is still unconnected.
 * Calling protect() on a freshly constructed, unbound Socket can fail with a
 * missing/invalid descriptor even if VPN consent was granted.
 *
 * Do not connect or send traffic until this method returns true.
 */
internal object ReceiverSocketSetup {
    fun <T> bindThenProtect(socket: T, bind: (T) -> Unit, protect: (T) -> Boolean): Boolean {
        bind(socket)
        return protect(socket)
    }
}

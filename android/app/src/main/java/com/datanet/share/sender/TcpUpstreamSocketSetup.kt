package com.datanet.share.sender

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Owner boundary for a newly allocated TCP upstream socket. TCP address/network
 * failover is limited to IOExceptions by AndroidUpstream, never arbitrary Errors.
 * Even if native resource exhaustion interrupts connect(), release ownership
 * and rethrow the original failure without logging destinations or credentials.
 */
internal fun connectSocketOrClose(socket: Socket, destination: InetSocketAddress, timeoutMs: Int): Socket {
    try {
        socket.connect(destination, timeoutMs)
        return socket
    } catch (failure: Throwable) {
        try { socket.close() }
        catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

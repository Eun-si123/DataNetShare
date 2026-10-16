package com.datanet.share.receiver

import com.datanet.share.common.Socks5
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Fail-closed liveness check: connect to the *same* sender's authenticated
 * SOCKS5 listener, without sending a destination request or routing any web
 * traffic. A failed probe does not automatically mean the peer is gone: the
 * watcher tolerates multiple consecutive failures.
 */
internal object SenderHealthProbe {
    fun check(host: String, port: Int, password: String, timeoutMs: Int = 3000,
              setup: (Socket) -> Boolean): Boolean {
        require(timeoutMs in 100..10_000)
        return try {
            Socket().use { socket ->
                if (!setup(socket)) return false
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                Socks5.authenticateClient(DataInputStream(socket.getInputStream()),
                    socket.getOutputStream(), password)
                true
            }
        } catch (_: Exception) {
            // Never log host/password or the exception's potentially sensitive message.
            false
        }
    }
}

/** Consecutive misses, not total misses, determine if a tunnel is unusable. */
internal class SenderHealthPolicy(private val missLimit: Int = 3) {
    private var misses = 0
    init { require(missLimit in 2..10) }

    @Synchronized fun observe(reachable: Boolean): Boolean {
        misses = if (reachable) 0 else (misses + 1).coerceAtMost(missLimit)
        return misses == missLimit
    }

    @Synchronized fun reset() { misses = 0 }
}

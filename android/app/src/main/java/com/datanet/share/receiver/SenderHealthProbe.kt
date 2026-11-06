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
              onSocketClosed: (Socket) -> Unit = {},
              socketFactory: () -> Socket = { Socket() },
              setup: (Socket) -> Boolean): Boolean {
        require(timeoutMs in 100..10_000)
        // Stop may interrupt the watchdog after its loop check but before
        // this probe begins. Do not prepare or connect a socket for that session.
        if (Thread.currentThread().isInterrupted) return false
        return try {
            val socket = socketFactory()
            try {
                socket.use {
                    // Stop can interrupt the watchdog while bind/protect is running.
                    if (!setup(socket) || Thread.currentThread().isInterrupted) return false
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    if (Thread.currentThread().isInterrupted) return false
                    Socks5.authenticateClient(DataInputStream(socket.getInputStream()),
                        socket.getOutputStream(), password,
                        beforeCredentials = {
                            !Thread.currentThread().isInterrupted && !socket.isClosed
                        })
                    !Thread.currentThread().isInterrupted && !socket.isClosed
                }
            } finally { onSocketClosed(socket) }
        } catch (_: InterruptedException) {
            // Stop interrupts the watchdog. Do not clear its cancellation signal
            // if socket preparation was interrupted before the next loop check.
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            // Never log host/password or the exception's potentially sensitive message.
            false
        }
    }
}

/** The VPN owner closes the active watchdog socket during Stop.
 * Register under the same lock as closeActive; a cancelled generation must
 * not publish a new socket after teardown or clear a subsequent session.
 */
internal class SenderHealthProbeSocketGuard {
    private var active: Socket? = null

    @Synchronized fun register(socket: Socket, isCurrent: () -> Boolean): Boolean {
        if (!isCurrent()) return false
        active = socket
        return true
    }

    @Synchronized fun unregister(socket: Socket) {
        if (active === socket) active = null
    }

    @Synchronized fun closeActive() {
        val previous = active
        active = null
        previous?.close()
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

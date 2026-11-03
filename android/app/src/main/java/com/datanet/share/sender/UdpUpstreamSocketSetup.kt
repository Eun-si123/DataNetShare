package com.datanet.share.sender

import android.system.ErrnoException
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Prepare a UDP socket on its selected route before any payload is sent.
 * Failed preparation must never leave a live socket behind.
 */
internal object UdpUpstreamSocketSetup {
    fun prepare(socket: DatagramSocket, destination: InetSocketAddress, bind: () -> Unit) {
        try {
            bind()
            socket.connect(destination)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }
}

/** Android may wrap a denied Network.bindSocket in SocketException (an IOException).
 * Check the typed errno cause, never localized exception-message text.
 * Linux EPERM=1 and EACCES=13 are stable ABI values; Android JVM test stubs
 * do not initialize OsConstants errno fields to their device values.
 */
internal fun isUdpBindingPermissionDenied(failure: IOException): Boolean {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = failure
    while (cause != null && seen.add(cause)) {
        if (cause is ErrnoException &&
            (cause.errno == 1 || cause.errno == 13)) return true
        cause = cause.cause
    }
    return false
}

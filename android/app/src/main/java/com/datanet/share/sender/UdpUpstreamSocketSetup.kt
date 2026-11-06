package com.datanet.share.sender

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
        } catch (failure: Throwable) {
            // This is solely a socket ownership boundary, not an error recovery
            // handler: even a VM/native worker Error must release the socket.
            socket.close()
            throw failure
        }
    }
}

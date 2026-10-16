package com.datanet.share.sender

import android.content.Context
import com.datanet.share.common.CellularNetworkTracker
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Resolve and connect through the same cellular Network. Never fall back silently. */
class AndroidUpstream(private val useDefaultRoute: Boolean) : Socks5Server.Upstream {
    override fun resolve(host: String): Array<InetAddress> = if (useDefaultRoute) {
        InetAddress.getAllByName(host)
    } else requireCellular().getAllByName(host)

    override fun connect(host: String, port: Int): Socket {
        if (port !in 1..65535) throw IOException("Invalid destination port")
        val network = if (useDefaultRoute) null else requireCellular()
        val addresses = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        var lastError: IOException? = null
        for (address in addresses) {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                socket.connect(InetSocketAddress(address, port), 15_000)
                return socket
            } catch (e: IOException) { socket.close(); lastError = e }
        }
        throw lastError ?: IOException("No address for destination")
    }

    override fun datagram(): DatagramSocket = DatagramSocket().also { socket ->
        try { if (!useDefaultRoute) requireCellular().bindSocket(socket) }
        catch (e: Exception) { socket.close(); throw IOException("Cellular UDP binding failed", e) }
    }

    private fun requireCellular() = CellularNetworkTracker.getCellularNetwork()
        ?: throw IOException("Mobile data is unavailable; no default-network fallback")
}

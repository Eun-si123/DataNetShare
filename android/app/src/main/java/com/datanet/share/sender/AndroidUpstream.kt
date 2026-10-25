package com.datanet.share.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.datanet.share.common.CellularNetworkTracker
import com.datanet.share.common.UpstreamRoutePolicy
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cellular first. Optional, explicitly visible Wi-Fi fallback is allowed only
 * on validated infrastructure Wi-Fi (never a local-only Wi-Fi Direct link).
 * Each new socket is bound to a specific selected Android Network.
 * Existing sockets are not migrated when the network disappears.
 */
class AndroidUpstream(
    private val useDefaultRoute: Boolean,
    context: Context,
    private val fallbackToWifi: Boolean = false
) : Socks5Server.Upstream {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private fun validatedWifi(): Network? = manager.allNetworks.firstOrNull { network ->
        val caps = manager.getNetworkCapabilities(network)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun networks(): List<Network> {
        val cellular = CellularNetworkTracker.getCellularNetwork()
        val wifi = if (fallbackToWifi) validatedWifi() else null
        val validatedCell = cellular != null && manager.getNetworkCapabilities(cellular)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val order = UpstreamRoutePolicy.order(cellular != null, validatedCell,
            wifi != null, fallbackToWifi)
        return order.mapNotNull { route ->
            when (route) {
                UpstreamRoutePolicy.Route.CELLULAR -> cellular
                UpstreamRoutePolicy.Route.WIFI -> wifi
            }
        }.ifEmpty { throw IOException("Mobile data is unavailable and no enabled validated Wi-Fi fallback exists") }
    }

    fun routeSummary(): String = try {
        if (useDefaultRoute) "Android default route"
        else {
            val first = networks().first()
            if (manager.getNetworkCapabilities(first)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                "Validated Wi-Fi fallback (cellular unavailable/unvalidated)"
            else if (fallbackToWifi) "Mobile data preferred · Wi-Fi fallback enabled"
            else "Mobile data only"
        }
    } catch (_: IOException) { "Waiting for mobile data / validated Wi-Fi" }

    override fun resolve(host: String): Array<InetAddress> {
        if (useDefaultRoute) return InetAddress.getAllByName(host)
        var last: IOException? = null
        for (network in networks()) {
            try { return network.getAllByName(host) }
            catch (e: IOException) { last = e }
        }
        throw last ?: IOException("DNS unavailable on selected networks")
    }

    override fun connect(host: String, port: Int): Socket {
        if (port !in 1..65535) throw IOException("Invalid destination port")
        if (useDefaultRoute) return connectOver(null, host, port)
        var last: IOException? = null
        for (network in networks()) {
            try { return connectOver(network, host, port) }
            catch (e: IOException) { last = e }
        }
        throw last ?: IOException("No usable upstream network")
    }

    private fun connectOver(network: Network?, host: String, port: Int): Socket {
        val addresses = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        var last: IOException? = null
        for (address in addresses) {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try { socket.connect(InetSocketAddress(address, port), 15_000); return socket }
            catch (e: IOException) { socket.close(); last = e }
        }
        throw last ?: IOException("No address for destination")
    }

    /** Fail over only during DNS/socket setup, before sending any UDP payload.
     * The resolved destination and bound socket must belong to the same Network.
     */
    override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
        if (port !in 1..65535) throw IOException("Invalid UDP destination port")
        val candidates: List<Network?> = if (useDefaultRoute) listOf(null) else networks()
        var last: IOException? = null
        for (network in candidates) {
            val addresses = try {
                network?.getAllByName(host) ?: InetAddress.getAllByName(host)
            } catch (e: IOException) {
                last = e
                continue
            }
            for (address in addresses) {
                val socket = DatagramSocket()
                try {
                    if (network != null) network.bindSocket(socket)
                    val destination = InetSocketAddress(address, port)
                    socket.connect(destination)
                    return Socks5Server.UdpConnection(socket, destination)
                } catch (e: Exception) {
                    socket.close()
                    last = IOException("UDP upstream setup failed", e)
                }
            }
        }
        throw last ?: IOException("No usable UDP upstream network")
    }

    override fun datagram(): DatagramSocket = DatagramSocket().also { socket ->
        try {
            if (!useDefaultRoute) networks().first().bindSocket(socket)
        } catch (e: Exception) { socket.close(); throw IOException("UDP upstream binding failed", e) }
    }
}

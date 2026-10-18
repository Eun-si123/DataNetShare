package com.datanet.share.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.datanet.share.common.InterfaceEnumerator
import com.datanet.share.common.PeerWifiConnector
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Keep protected sender sockets on the local link even if cellular is Android's default. */
class ReceiverLink(context: Context, host: String) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val peer = InetAddress.getByName(host) as Inet4Address
    private fun matches(local: InetAddress, prefix: Int): Boolean {
        if (local !is Inet4Address || prefix !in 1..32) return false
        val a = java.nio.ByteBuffer.wrap(local.address).int
        val b = java.nio.ByteBuffer.wrap(peer.address).int
        val mask = -1 shl (32 - prefix)
        return a and mask == b and mask
    }
    private fun network(): Network? = (listOfNotNull(PeerWifiConnector.network) + manager.allNetworks.asList()).firstOrNull { network ->
        val caps = manager.getNetworkCapabilities(network)
        caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            manager.getLinkProperties(network)?.linkAddresses?.any { matches(it.address, it.prefixLength) } == true
    }
    private fun localAddress(): InetAddress {
        for (candidate in InterfaceEnumerator.localCandidates()) {
            val iface = NetworkInterface.getByName(candidate.name) ?: continue
            iface.interfaceAddresses.firstOrNull { matches(it.address, it.networkPrefixLength.toInt()) }?.let { return it.address }
        }
        throw IOException("No local link to the sender; join its Wi-Fi network or check the address")
    }
    fun bind(socket: Socket) { val net = network(); if (net != null) net.bindSocket(socket) else socket.bind(InetSocketAddress(localAddress(), 0)) }
    fun bind(socket: DatagramSocket) { val net = network(); if (net != null) net.bindSocket(socket) else socket.bind(InetSocketAddress(localAddress(), 0)) }
}

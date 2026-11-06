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
    private fun network(): Network? = (listOfNotNull(PeerWifiConnector.network) + manager.allNetworks.asList()).firstOrNull { network ->
        val caps = manager.getNetworkCapabilities(network)
        caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            manager.getLinkProperties(network)?.linkAddresses?.any { ReceiverLinkAddressPolicy.sameIpv4Prefix(it.address, peer, it.prefixLength) } == true
    }
    private fun localAddress(): InetAddress {
        for (candidate in InterfaceEnumerator.localCandidates()) {
            // A private address on tun/wg/etc. is not evidence of a physical
            // sender link; binding to it can route SOCKS traffic into a VPN.
            if (!ReceiverLinkFallbackPolicy.allows(candidate.name)) continue
            val iface = NetworkInterface.getByName(candidate.name) ?: continue
            iface.interfaceAddresses.firstOrNull { ReceiverLinkAddressPolicy.sameIpv4Prefix(it.address, peer, it.networkPrefixLength.toInt()) }?.let { return it.address }
        }
        throw IOException("No local link to the sender; join its Wi-Fi network or check the address")
    }
    /** The physical local-only network carrying protected SOCKS traffic; null when only an interface is known. */
    fun underlyingNetwork(): Network? = network()
    fun bind(socket: Socket) { val net = network(); if (net != null) net.bindSocket(socket) else socket.bind(InetSocketAddress(localAddress(), 0)) }
    fun bind(socket: DatagramSocket) { val net = network(); if (net != null) net.bindSocket(socket) else socket.bind(InetSocketAddress(localAddress(), 0)) }
}

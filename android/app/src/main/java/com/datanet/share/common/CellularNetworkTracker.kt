package com.datanet.share.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Keeps cellular requested while the sender's Wi-Fi link has no internet. */
object CellularNetworkTracker {
    @Volatile private var cellular: Network? = null
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val candidates = CellularRouteCandidates<Network>()

    @Synchronized fun start(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        manager = cm
        candidates.clear()
        cm.allNetworks.forEach { network ->
            val caps = cm.getNetworkCapabilities(network)
            candidates.observe(network, caps != null && CellularRoutePolicy.eligible(
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ))
        }
        cellular = candidates.selected()
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Use the same monitor as start()/stop(): an in-flight callback must not
            // publish a stale network after Stop clears the active session.
            // API 26+ delivers capabilities after onAvailable. Do not publish
            // an unclassified network: a VPN may advertise cellular transport.
            override fun onAvailable(network: Network) = Unit
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                synchronized(this@CellularNetworkTracker) {
                    if (callback === this) {
                        candidates.observe(network, CellularRoutePolicy.eligible(
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        ))
                        cellular = candidates.selected()
                    }
                }
            override fun onLost(network: Network) = synchronized(this@CellularNetworkTracker) {
                if (callback === this) {
                    candidates.observe(network, false)
                    cellular = candidates.selected()
                }
            }
        }
        callback = cb
        try {
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        } catch (e: Exception) {
            callback = null; cellular = null; candidates.clear(); manager = null
            throw e
        }
    }
    @Synchronized fun stop() {
        val cb = callback; callback = null
        try { if (cb != null) manager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        cellular = null; candidates.clear(); manager = null
    }
    fun getCellularNetwork(): Network? = cellular
    fun cellularAvailable(): Boolean = cellular != null
}

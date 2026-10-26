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

    @Synchronized fun start(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        manager = cm
        cellular = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.let {
                CellularRoutePolicy.eligible(
                    it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                )
            } == true
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Use the same monitor as start()/stop(): an in-flight callback must not
            // publish a stale network after Stop clears the active session.
            // API 26+ delivers capabilities after onAvailable. Do not publish
            // an unclassified network: a VPN may advertise cellular transport.
            override fun onAvailable(network: Network) = Unit
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                synchronized(this@CellularNetworkTracker) {
                    if (callback === this) {
                        if (CellularRoutePolicy.eligible(
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            )) cellular = network
                        else if (cellular == network) cellular = null
                    }
                }
            override fun onLost(network: Network) = synchronized(this@CellularNetworkTracker) {
                if (callback === this && cellular == network) cellular = null
            }
        }
        callback = cb
        try {
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        } catch (e: Exception) { callback = null; cellular = null; throw e }
    }
    @Synchronized fun stop() {
        val cb = callback; callback = null
        try { if (cb != null) manager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        cellular = null; manager = null
    }
    fun getCellularNetwork(): Network? = cellular
    fun cellularAvailable(): Boolean = cellular != null
}

package com.datanet.share.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Android 10+ QR peer Wi-Fi request. Requires system approval, is local-only,
 * and must remain registered throughout the receiver VPN session.
 * Never logs or persists credentials. The caller releases it on explicit Stop.
 */
object PeerWifiConnector {
    enum class Status { IDLE, REQUESTING, CONNECTED, UNAVAILABLE, LOST }
    @Volatile var status = Status.IDLE
        private set
    @Volatile var network: Network? = null
        private set
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: ((Status) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private fun announce(source: ConnectivityManager.NetworkCallback, value: Status) {
        main.post {
            synchronized(this) {
                if (callback === source && status == value) listener?.invoke(value)
            }
        }
    }

    @Synchronized fun setListener(value: ((Status) -> Unit)?) {
        listener = value
        if (value != null) main.post { value(status) }
    }

    @Synchronized fun request(context: Context, ssid: String, password: String) {
        require(Build.VERSION.SDK_INT >= 29) { "Android 10+ required" }
        require(ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= 32)
        require(password.length in 8..63 && password.all { it.code in 32..126 })
        release()
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid)
            .setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                // Wait until IPv4 link properties are provisioned before the
                // receiver starts its authenticated SOCKS probe.
            }
            override fun onLinkPropertiesChanged(value: Network, properties: android.net.LinkProperties) {
                if (properties.linkAddresses.none { it.address is java.net.Inet4Address }) return
                synchronized(this@PeerWifiConnector) {
                    if (callback !== this) return
                    network = value
                    status = Status.CONNECTED
                }
                announce(this, Status.CONNECTED)
            }
            override fun onUnavailable() {
                synchronized(this@PeerWifiConnector) {
                    if (callback !== this) return
                    network = null
                    status = Status.UNAVAILABLE
                }
                announce(this, Status.UNAVAILABLE)
            }
            override fun onLost(value: Network) {
                synchronized(this@PeerWifiConnector) {
                    if (callback !== this || network != value) return
                    network = null
                    status = Status.LOST
                }
                announce(this, Status.LOST)
            }
        }
        manager = cm
        callback = cb
        status = Status.REQUESTING
        try { cm.requestNetwork(request, cb, 30_000) }
        catch (e: Exception) { release(); throw e }
    }

    @Synchronized fun release() {
        val cb = callback
        callback = null
        try { if (cb != null) manager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        manager = null
        network = null
        status = Status.IDLE
    }
}

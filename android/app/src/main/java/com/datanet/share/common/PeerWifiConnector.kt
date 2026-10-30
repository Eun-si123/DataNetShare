package com.datanet.share.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.io.IOException

/**
 * Android 10+ QR peer Wi-Fi request.
 *
 * IPv4 being assigned is NOT evidence that a Wi-Fi Direct sender is reachable:
 * validate the exact requested Network with a TCP connection to the sender's
 * local SOCKS port before asking the user to grant a VPN. No secrets are sent
 * by the TCP readiness check; the VPN service subsequently authenticates.
 *
 * Only the Android system can approve the Wi-Fi request. Do not silently join,
 * mutate saved networks, or bind the entire process to a no-Internet network.
 */
object PeerWifiConnector {
    enum class Status { IDLE, REQUESTING, CHECKING_SENDER, CONNECTED, UNAVAILABLE, SENDER_UNREACHABLE, LOST }
    @Volatile var status = Status.IDLE
        private set
    @Volatile var network: Network? = null
        private set
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    // Remember onAvailable even before IPv4 arrives: onLost may precede link properties.
    private var observedNetwork: Network? = null
    private var verifyingNetwork: Network? = null
    private var verifier: Thread? = null
    private var generation = 0
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
        if (value != null) main.post {
            synchronized(this) { if (listener === value) value(status) }
        }
    }

    @androidx.annotation.RequiresApi(29)
    @Synchronized fun request(context: Context, ssid: String, password: String, senderHost: String, senderPort: Int) {
        require(Build.VERSION.SDK_INT >= 29) { "Android 10+ required" }
        require(ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= 32)
        require(password.length in 8..63 && password.all { it.code in 32..126 })
        require(Endpoint.isIpv4(senderHost) && senderPort in 1..65535)
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
                // A network can disappear before IPv4 provisioning; remember it for onLost.
                synchronized(this@PeerWifiConnector) {
                    if (callback === this && QrNetworkLossPolicy.acceptsNetworkUpdate(
                        status, Status.LOST, Status.UNAVAILABLE)) observedNetwork = value
                }
            }
            override fun onLinkPropertiesChanged(value: Network, properties: LinkProperties) {
                if (properties.linkAddresses.none { it.address is Inet4Address }) return
                val token: Int
                synchronized(this@PeerWifiConnector) {
                    if (callback !== this || !QrNetworkLossPolicy.acceptsNetworkUpdate(
                        status, Status.LOST, Status.UNAVAILABLE) ||
                        status == Status.CONNECTED || verifyingNetwork == value ||
                        (observedNetwork != null && observedNetwork != value)) return
                    verifier?.interrupt()
                    verifyingNetwork = value
                    network = null
                    status = Status.CHECKING_SENDER
                    token = ++generation
                }
                announce(this, Status.CHECKING_SENDER)
                val source = this
                val worker = Thread({
                    val reachable = QrPeerProbePolicy.reachable(connect = {
                        // Network.socketFactory binds only this socket; leaving
                        // cellular/default routes and Android VPN settings intact.
                        value.socketFactory.createSocket().use { socket ->
                            socket.connect(InetSocketAddress(senderHost, senderPort),
                                QrPeerProbePolicy.CONNECT_TIMEOUT_MS)
                        }
                    })
                    synchronized(this@PeerWifiConnector) {
                        if (callback !== source || verifyingNetwork != value || token != generation) return@Thread
                        verifyingNetwork = null
                        verifier = null
                        network = if (reachable) value else null
                        status = if (reachable) Status.CONNECTED else Status.SENDER_UNREACHABLE
                    }
                    announce(source, if (reachable) Status.CONNECTED else Status.SENDER_UNREACHABLE)
                }, "qr-peer-readiness").apply { isDaemon = true }
                synchronized(this@PeerWifiConnector) {
                    if (callback !== source || token != generation) return
                    verifier = worker
                }
                worker.start()
            }
            override fun onUnavailable() {
                synchronized(this@PeerWifiConnector) {
                    if (callback !== this || !QrNetworkLossPolicy.acceptsUnavailable(
                        status, Status.LOST, Status.UNAVAILABLE, Status.CONNECTED)) return
                    verifier?.interrupt()
                    verifier = null
                    observedNetwork = null
                    verifyingNetwork = null
                    network = null
                    ++generation
                    status = Status.UNAVAILABLE
                }
                announce(this, Status.UNAVAILABLE)
            }
            override fun onLost(value: Network) {
                synchronized(this@PeerWifiConnector) {
                    if (callback !== this) return
                    val shouldMarkLost = QrNetworkLossPolicy.shouldMarkLost(
                        value, observedNetwork, verifyingNetwork, network)
                    if (observedNetwork == value) observedNetwork = null
                    if (!shouldMarkLost) return
                    verifier?.interrupt()
                    verifier = null
                    verifyingNetwork = null
                    network = null
                    ++generation
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
        ++generation
        verifier?.interrupt()
        verifier = null
        observedNetwork = null
        verifyingNetwork = null
        val cb = callback
        callback = null
        try { if (cb != null) manager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        manager = null
        network = null
        status = Status.IDLE
    }
}

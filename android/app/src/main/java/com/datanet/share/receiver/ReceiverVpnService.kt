package com.datanet.share.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.datanet.share.R
import com.datanet.share.common.Constants
import com.datanet.share.common.NetworkEvent
import com.datanet.share.common.PeerWifiConnector
import com.datanet.share.common.ServiceNotifications
import com.datanet.share.common.SessionStore
import com.datanet.share.common.Socks5
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

class ReceiverVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.datanet.share.receiver.VPN_START"
        const val ACTION_STOP = "com.datanet.share.receiver.VPN_STOP"
        const val EXTRA_SOCKS_HOST = "socks_host"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_PASSWORD = "proxy_password"
        const val EXTRA_RELEASE_PEER_ON_LOSS = "release_peer_on_loss"
    }
    private var pfd: ParcelFileDescriptor? = null
    private var bridge: Tun2Socks? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var probe: Socket? = null
    private var releasePeerOnLoss = false
    @Volatile private var physicalUnderlying: Network? = null
    @Volatile private var lastNetworkCheckAtMs = 0L
    @Volatile private var networkStatus = "Network state not yet checked"
    @Volatile private var peerWatchdog: Thread? = null
    @Volatile private var recentTcpError: String? = null
    @Volatile private var recentUdpError: String? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { cleanup(); com.datanet.share.common.PeerWifiConnector.release(); SessionStore.stopped(SessionStore.Mode.RECEIVER); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        recentTcpError = null
        recentUdpError = null
        releasePeerOnLoss = intent?.getBooleanExtra(EXTRA_RELEASE_PEER_ON_LOSS, false) ?: false
        if (!SessionStore.claim(SessionStore.Mode.RECEIVER)) {
            if (SessionStore.current.mode != SessionStore.Mode.RECEIVER) stopSelf()
            return START_NOT_STICKY
        }
        try {
            check(prepare(this) == null) { "Allow the VPN connection before starting" }
            val host = intent.getStringExtra(EXTRA_SOCKS_HOST) ?: ""
            val port = intent.getIntExtra(EXTRA_SOCKS_PORT, Constants.SOCKS5_PORT)
            val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
            require(com.datanet.share.common.Endpoint.isIpv4(host) && port in 1..65535) { "Enter the sender's IPv4 address and port" }
            ServiceCompat.startForeground(this, Constants.RECEIVER_NOTIFICATION_ID, notification("Checking sender and proxy password…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            val token = ++generation
            // Probe before VPN installation. QR Wi-Fi can have IPv4 provisioned
            // before the group owner is actually reachable on the local link.
            Thread({
                try {
                    val qrRequestedNetwork = PeerWifiConnector.network != null
                    val link = ReceiverLink(this, host)
                    for (attempt in 1..SenderProbeRetryPolicy.MAX_ATTEMPTS) {
                        if (generation != token) return@Thread
                        val socket = Socket()
                        probe = socket
                        try {
                            socket.use {
                                check(ReceiverSocketSetup.bindThenProtect(it,
                                    { candidate -> link.bind(candidate) },
                                    { candidate -> protect(candidate) })) {
                                    "Could not protect sender socket (VPN permission revoked or protection denied)"
                                }
                                it.soTimeout = 10_000
                                it.connect(InetSocketAddress(host, port),
                                    if (qrRequestedNetwork) SenderProbeRetryPolicy.CONNECT_TIMEOUT_MS else 10_000)
                                Socks5.authenticateClient(DataInputStream(it.getInputStream()),
                                    it.getOutputStream(), password)
                            }
                            break
                        } catch (e: Exception) {
                            if (!SenderProbeRetryPolicy.shouldRetry(e, attempt, qrRequestedNetwork)) throw e
                            try { Thread.sleep(SenderProbeRetryPolicy.BETWEEN_ATTEMPTS_MS) }
                            catch (_: InterruptedException) { return@Thread }
                        } finally {
                            if (probe === socket) probe = null
                        }
                    }
                    main.post { if (generation == token) startTunnel(host, port, password) }
                } catch (e: Exception) {
                    main.post {
                        if (generation == token) {
                            val message = if (SenderProbeRetryPolicy.routeError(e))
                                getString(R.string.sender_peer_route_failed)
                            else "Sender check failed: ${e.message}"
                            fail(message)
                        }
                    }
                }
            }, "receiver-check").apply { isDaemon = true; start() }
        } catch (e: Exception) { fail(e.message ?: "Receiver could not start") }
        return START_NOT_STICKY
    }

    private fun startTunnel(host: String, port: Int, password: String) {
        try {
            // The peer is reached through a deliberately bound local-only Wi-Fi
            // Network, not necessarily Android's default Internet network.
            // Tell Android what actually carries the protected SOCKS connection.
            val underlying = ReceiverLink(this, host).underlyingNetwork()
            val builder = Builder().setSession("DataNet Share · experimental receiver")
                .addAddress(Constants.VPN_ADDRESS, 24).addRoute("0.0.0.0", 0)
                .addDnsServer(Constants.TUNNELED_DNS).setMtu(1500)
                .setBlocking(false)
            if (underlying != null) builder.setUnderlyingNetworks(arrayOf(underlying))
            val descriptor = builder.establish() ?: error("VPN permission was revoked")
            physicalUnderlying = underlying
            lastNetworkCheckAtMs = 0L
            pfd = descriptor
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataNetShare::Receiver").apply { acquire() }
            val token = generation
            val link = ReceiverLink(this, host)
            bridge = Tun2Socks(descriptor.fileDescriptor, host, port, password,
                { socket -> ReceiverSocketSetup.bindThenProtect(socket, { link.bind(it) }, { protect(it) }) },
                { socket -> ReceiverSocketSetup.bindThenProtect(socket, { link.bind(it) }, { protect(it) }) },
                onFailure = { message -> main.post { if (generation == token) fail(message) } },
                onStats = { metrics ->
                    if (generation == token) {
                        val tcpError = recentTcpError
                        val udpError = recentUdpError
                        val connectivity = networkConnectivityDiagnostics()
                        val perf = "TCP retransmits ${metrics.tcpRetransmits}" +
                            " · TUN waits ${metrics.tunWriteWaits} (${metrics.tunWriteWaitMs} ms total)"
                        SessionStore.telemetry.postValue(
                            "Receiver TCP attempts ${metrics.tcpAttempts} / connected ${metrics.tcpConnected}" +
                            "\nUDP attempts ${metrics.udpAttempts} / associated ${metrics.udpConnected} (active ${metrics.activeUdp})" +
                            "\nTUN drops ${metrics.tunDropped} · $perf" +
                            "\nTCP idle reclaimed ${metrics.idleTcpReclaimed} · UDP queue overflows ${metrics.udpQueueOverflows}" +
                            "\nTransfer RX ${metrics.rxBytes} B / TX ${metrics.txBytes} B" +
                            "\n" + connectivity)
                        SessionStore.stats.postValue("${metrics.activeTcp} TCP flow(s) · ↓ ${metrics.rxBytes / 1024} KB · ↑ ${metrics.txBytes / 1024} KB" +
                            "\nTCP attempts ${metrics.tcpAttempts} / connected ${metrics.tcpConnected}" +
                            " · UDP attempts ${metrics.udpAttempts} / associated ${metrics.udpConnected} (active ${metrics.activeUdp})" +
                            " · TUN drops ${metrics.tunDropped}" +
                            "\nTCP idle reclaimed ${metrics.idleTcpReclaimed} · UDP queue overflows ${metrics.udpQueueOverflows}" +
                            "\n$perf" + "\n" + connectivity +
                            (if (tcpError.isNullOrEmpty()) "" else "\nLast TCP error: $tcpError") +
                            (if (udpError.isNullOrEmpty()) "" else "\nLast UDP error: $udpError"))
                    }
                },
                onTcpError = { error ->
                    if (generation == token) {
                        recentTcpError = error
                        val code = when {
                            error.contains("capacity") -> NetworkEvent.TCP_FLOW_LIMIT
                            error.contains("upload") -> NetworkEvent.TCP_UPLOAD
                            error.contains("idle timeout") -> NetworkEvent.TCP_IDLE
                            error.contains("retransmission") -> NetworkEvent.TCP_RETRANSMIT
                            error.contains("congestion") -> NetworkEvent.TUN_CONGESTION
                            else -> NetworkEvent.TCP_UPSTREAM
                        }
                        SessionStore.note(code)
                    }
                },
                onUdpError = { error ->
                    if (generation == token) {
                        recentUdpError = error
                        SessionStore.note(when {
                            error.contains("capacity") -> NetworkEvent.UDP_FLOW_LIMIT
                            error.contains("queue full") -> NetworkEvent.UDP_QUEUE
                            error.contains("read") -> NetworkEvent.UDP_RELAY
                            else -> NetworkEvent.UDP_SETUP
                        })
                    }
                })
                .also { it.start() }
            SessionStore.publish(SessionStore.State(SessionStore.Mode.RECEIVER, "active", "Experimental IPv4 VPN via $host:$port", host))
            getSystemService(NotificationManager::class.java).notify(Constants.RECEIVER_NOTIFICATION_ID, notification("IPv4 tunnel via $host:$port"))
            startPeerWatchdog(token, host, port, password)
        } catch (e: Exception) { fail("VPN could not start: ${e.message}") }
    }

    /**
     * Sender and receiver are on separate devices: Android doesn't signal the
     * sender's foreground-service shutdown to this VPN. Probe the authenticated
     * local SOCKS endpoint every five seconds and close the VPN after 3 misses.
     * Transient Wi-Fi Direct stalls should not immediately drop the tunnel.
     *
     * All data sockets are protected and bound to the same local peer link.
     * Generation checks suppress stale callbacks after explicit Stop/restart.
     */
    private fun startPeerWatchdog(token: Int, host: String, port: Int, password: String) {
        val watcher = Thread({
            val policy = SenderHealthPolicy(3)
            val link = ReceiverLink(this, host)
            while (generation == token && !Thread.currentThread().isInterrupted) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
                if (generation != token) break
                // A protected peer probe may still succeed over Wi-Fi Direct
                // even after Android has replaced/revoked this app's system VPN.
                val permissionLost = try { prepare(this) != null } catch (_: Exception) { true }
                if (permissionLost) {
                    main.post {
                        if (generation == token) {
                            SessionStore.note(NetworkEvent.VPN_SERVICE_ENDED)
                            fail("Android VPN permission was revoked or another VPN replaced it.")
                        }
                    }
                    break
                }
                // Reflect only the actual local sender link used by the protected
                // SOCKS sockets. The network might change after Wi-Fi reconnect.
                val currentUnderlying = try { link.underlyingNetwork() } catch (_: Exception) { null }
                if (currentUnderlying != null && currentUnderlying != physicalUnderlying) {
                    try {
                        if (setUnderlyingNetworks(arrayOf(currentUnderlying))) {
                            physicalUnderlying = currentUnderlying
                            lastNetworkCheckAtMs = 0L
                        }
                    } catch (_: Exception) { /* keep the existing VPN; health probe decides reachability */ }
                }
                val alive = SenderHealthProbe.check(host, port, password,
                    setup = { socket ->
                        ReceiverSocketSetup.bindThenProtect(socket,
                            { link.bind(it) }, { protect(it) })
                    })
                if (generation != token) break
                if (policy.observe(alive)) {
                    main.post {
                        if (generation == token && SessionStore.current.mode == SessionStore.Mode.RECEIVER) {
                            SessionStore.note(NetworkEvent.SENDER_DISCONNECTED)
                            fail("Sender disconnected or unreachable. Receiver VPN was stopped; reconnect when sharing resumes.", senderLost = true)
                        }
                    }
                    break
                }
            }
        }, "receiver-peer-watchdog")
        watcher.isDaemon = true
        peerWatchdog = watcher
        watcher.start()
    }

    /**
     * These are Android capability declarations, not proof that every remote
     * service works. In particular QR Wi-Fi is intentionally local-only.
     * Sample at most once per five seconds to avoid slowing the VPN bridge.
     * Do not report network addresses, SSIDs or credentials.
     */
    private fun networkConnectivityDiagnostics(): String {
        val now = SystemClock.elapsedRealtime()
        if (lastNetworkCheckAtMs != 0L && now - lastNetworkCheckAtMs < 5_000L) return networkStatus
        lastNetworkCheckAtMs = now
        networkStatus = try {
            val cm = getSystemService(ConnectivityManager::class.java)
            fun describe(caps: NetworkCapabilities?): String {
                if (caps == null) return "unknown"
                val inet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                return "internet=$inet, validated=$validated, vpn=$vpn, wifi=$wifi"
            }
            val vpn = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            val underlying = physicalUnderlying
            val vpnCaps = vpn?.let { cm.getNetworkCapabilities(it) }
            val underlyingCaps = underlying?.let { cm.getNetworkCapabilities(it) }
            "Android VPN network: ${describe(vpnCaps)}" +
                "\nSender link (local-only expected): ${describe(underlyingCaps)}" +
                "\nQR Wi-Fi request active: ${PeerWifiConnector.network != null}"
        } catch (_: Exception) {
            "Android network capabilities unavailable"
        }
        return networkStatus
    }

    private fun notification(text: String) = ServiceNotifications.build(this, "DataNet Share · Receiver", text,
        Intent(this, ReceiverVpnService::class.java).setAction(ACTION_STOP))
    private fun fail(message: String, senderLost: Boolean = false) {
        cleanup()
        if (!senderLost || releasePeerOnLoss) com.datanet.share.common.PeerWifiConnector.release()
        SessionStore.publish(SessionStore.State(SessionStore.Mode.RECEIVER, "failed", message)); stopSelf()
    }
    private fun cleanup() {
        // Invalidate callbacks before tearing down the VPN. Stop both explicit
        // app button and notification action through this same cleanup path.
        ++generation
        physicalUnderlying = null
        lastNetworkCheckAtMs = 0L
        networkStatus = "Network state not yet checked"
        peerWatchdog?.interrupt(); peerWatchdog = null
        try { probe?.close() } catch (_: Exception) {}
        probe = null
        // FIRST drop Android's actual VPN tunnel/routes even if a TCP worker is
        // stuck in a socket call or bridge shutdown. Do not leave a black-hole VPN.
        val descriptor = pfd; pfd = null
        try { descriptor?.close() } catch (_: Exception) {}
        val oldBridge = bridge; bridge = null
        try { oldBridge?.stop() } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() {
        // If Android destroys the running VPN service without the app/notification
        // STOP action, do not silently present "Ready" while connectivity is gone.
        val unexpected = SessionStore.current.mode == SessionStore.Mode.RECEIVER &&
            SessionStore.current.busy
        cleanup()
        if (releasePeerOnLoss) com.datanet.share.common.PeerWifiConnector.release()
        if (unexpected) {
            SessionStore.note(NetworkEvent.VPN_SERVICE_ENDED)
            SessionStore.publish(SessionStore.State(SessionStore.Mode.RECEIVER, "failed",
                "Receiver VPN service stopped unexpectedly. Reconnect to resume sharing."))
        }
        super.onDestroy()
    }
    override fun onRevoke() {
        SessionStore.note(NetworkEvent.VPN_SERVICE_ENDED)
        fail("Android revoked this VPN connection")
    }
}

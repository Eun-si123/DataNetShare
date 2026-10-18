package com.datanet.share.sender

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Observer
import com.datanet.share.common.CellularNetworkTracker
import com.datanet.share.common.Constants
import com.datanet.share.common.FormatBytes
import com.datanet.share.common.InterfaceEnumerator
import com.datanet.share.common.NetworkEvent
import com.datanet.share.common.ServiceNotifications
import com.datanet.share.common.SessionStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class SenderService : Service() {
    companion object {
        const val ACTION_START = "com.datanet.share.sender.START"
        const val ACTION_START_LOCAL = "com.datanet.share.sender.START_LOCAL"
        const val ACTION_STOP = "com.datanet.share.sender.STOP"
        const val EXTRA_BIND_HOST = "bind_host"
        const val EXTRA_DEFAULT_ROUTE = "default_route"
        const val EXTRA_WIFI_FALLBACK = "wifi_fallback"
        const val EXTRA_PROXY_PASSWORD = "custom_proxy_password"
        const val EXTRA_P2P_SUFFIX = "p2p_ssid_suffix"
        const val EXTRA_P2P_PASSWORD = "p2p_wifi_password"
        const val EXTRA_P2P_BAND = "p2p_band"
    }
    private var wifiHost: WifiDirectHost? = null
    private var socks: Socks5Server? = null
    private var observer: Observer<WifiDirectHost.State>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var defaultRoute = false
    private var wifiFallback = false
    private var sessionPasswordOverride = ""
    private val idleReclaimed = AtomicLong()
    @Volatile private var recentUpstreamFailure: String? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session = 0
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { cleanup(); SessionStore.stopped(SessionStore.Mode.SENDER); stopSelf(); return START_NOT_STICKY }
        if (intent?.action !in listOf(ACTION_START, ACTION_START_LOCAL)) { stopSelf(); return START_NOT_STICKY }
        if (!SessionStore.claim(SessionStore.Mode.SENDER)) {
            if (SessionStore.current.mode != SessionStore.Mode.SENDER) stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, Constants.SENDER_NOTIFICATION_ID, notification("Starting sharing…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataNetShare::Sender").apply { acquire() }
            defaultRoute = intent?.getBooleanExtra(EXTRA_DEFAULT_ROUTE, false) ?: false
            wifiFallback = !defaultRoute && (intent?.getBooleanExtra(EXTRA_WIFI_FALLBACK, false) ?: false)
            sessionPasswordOverride = intent?.getStringExtra(EXTRA_PROXY_PASSWORD).orEmpty()
            require(sessionPasswordOverride.isEmpty() || com.datanet.share.common.ProxyPasswordPolicy.valid(sessionPasswordOverride)) { "Invalid SOCKS5 password" }
            recentUpstreamFailure = null
            idleReclaimed.set(0)
            // Do not hold a cellular network up when the user explicitly selected Android routing.
            if (!defaultRoute) CellularNetworkTracker.start(this)
            val token = ++session
            if (intent?.action == ACTION_START_LOCAL) {
                val host = intent.getStringExtra(EXTRA_BIND_HOST) ?: ""
                require(InterfaceEnumerator.localCandidates().any { it.ip == host }) { "Choose an available USB or Wi-Fi address first" }
                openProxy(token, host)
            } else {
                val host = WifiDirectHost(this); wifiHost = host
                observer = Observer<WifiDirectHost.State> { state ->
                    if (token != session) return@Observer
                    when (state) {
                        is WifiDirectHost.State.GroupReady -> openProxy(token, state.groupOwnerIp, state.ssid, state.passphrase)
                        is WifiDirectHost.State.Failed -> fail("${state.reason}\n${state.hint}")
                        is WifiDirectHost.State.Initializing, is WifiDirectHost.State.CreatingGroup -> status("Preparing Wi-Fi Direct group…")
                        is WifiDirectHost.State.Retrying -> status("Wi-Fi Direct: ${state.reason}; retry ${state.attempt}/${state.maxAttempts}")
                        else -> Unit
                    }
                }.also { host.state.observeForever(it) }
                val bandName = intent?.getStringExtra(EXTRA_P2P_BAND).orEmpty()
                val band = WifiDirectProfile.Band.entries.firstOrNull { it.name == bandName }
                    ?: WifiDirectProfile.Band.AUTO
                host.start(WifiDirectProfile(
                    nameSuffix = intent?.getStringExtra(EXTRA_P2P_SUFFIX).orEmpty(),
                    passphrase = intent?.getStringExtra(EXTRA_P2P_PASSWORD).orEmpty(),
                    band = band
                ))
            }
        } catch (e: Exception) { fail(e.message ?: "Sharing could not start") }
        return START_NOT_STICKY
    }

    private fun openProxy(token: Int, host: String, ssid: String = "", wifiPassword: String = "") {
        if (socks != null || token != session) return
        val password = sessionPasswordOverride.ifEmpty { ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } }
        val upstream = AndroidUpstream(defaultRoute, this, wifiFallback)
        val server = Socks5Server(host, Constants.SOCKS5_PORT, password, upstream,
            onStats = { clients, rx, tx ->
                if (token == session) {
                    val route = if (defaultRoute) describeDefaultRoute() else upstream.routeSummary()
                    val recent = recentUpstreamFailure
                    val idleCount = idleReclaimed.get()
                    SessionStore.telemetry.postValue("Sender clients $clients · RX $rx B · TX $tx B · idle TCP reclaimed $idleCount")
                    SessionStore.stats.postValue("$clients connection(s) · ${FormatBytes.formatRxTx(rx, tx)}\n$route" +
                        (if (idleCount == 0L) "" else "\nIdle TCP connections cleaned up: $idleCount") +
                        (if (recent.isNullOrEmpty()) "" else "\nLast upstream error: $recent"))
                }
            }, onFailure = { error -> main.post { if (token == session) fail(error) } },
            onUpstreamFailure = { error -> if (token == session) { recentUpstreamFailure = error; SessionStore.note(NetworkEvent.SENDER_UPSTREAM) } },
            onClientLimit = { if (token == session) SessionStore.note(NetworkEvent.SENDER_CLIENT_LIMIT) },
            onIdleClose = { if (token == session) idleReclaimed.incrementAndGet() },
            onPeers = { peers -> if (token == session) SessionStore.peerUsage.postValue(peers) })
        socks = server
        try {
            server.start()
            SessionStore.publish(SessionStore.State(SessionStore.Mode.SENDER, "active",
                "SOCKS5: $host:${Constants.SOCKS5_PORT}\nUsername: datanet\n${if (defaultRoute) "Upstream: Android default route" else if (wifiFallback) "Upstream: cellular, fallback to validated Wi-Fi" else "Upstream: mobile data only"}", host, password, ssid, wifiPassword))
            getSystemService(NotificationManager::class.java).notify(Constants.SENDER_NOTIFICATION_ID, notification("SOCKS5 at $host:${Constants.SOCKS5_PORT} · tap for credentials"))
        } catch (e: Exception) { fail("Proxy could not start: ${e.message}") }
    }

    private fun describeDefaultRoute(): String {
        // Report what Android currently selects, not an unverified promise of Wi-Fi egress.
        val manager = getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val path = when {
            caps == null -> "no active route"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other/unknown"
        }
        return "Android default route: $path"
    }

    private fun status(details: String) { SessionStore.publish(SessionStore.current.copy(details = details)) }
    private fun fail(details: String) {
        cleanup()
        SessionStore.publish(SessionStore.State(SessionStore.Mode.SENDER, "failed", details))
        stopSelf()
    }
    private fun notification(text: String) = ServiceNotifications.build(this, "DataNet Share · Sender", text,
        Intent(this, SenderService::class.java).setAction(ACTION_STOP))

    private fun cleanup() {
        ++session
        observer?.let { wifiHost?.state?.removeObserver(it) }; observer = null
        socks?.stop(); socks = null
        wifiHost?.stop(); wifiHost = null
        CellularNetworkTracker.stop()
        sessionPasswordOverride = ""
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() {
        cleanup()
        if (SessionStore.current.status != "failed") SessionStore.stopped(SessionStore.Mode.SENDER)
        super.onDestroy()
    }
}

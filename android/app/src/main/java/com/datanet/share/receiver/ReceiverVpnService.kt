package com.datanet.share.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.datanet.share.common.Constants
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
    }
    private var pfd: ParcelFileDescriptor? = null
    private var bridge: Tun2Socks? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var probe: Socket? = null
    @Volatile private var recentTcpError: String? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { cleanup(); SessionStore.stopped(SessionStore.Mode.RECEIVER); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        recentTcpError = null
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
            // Probe before installing routes so wrong addresses/passwords do not black-hole other apps.
            val socket = Socket(); probe = socket
            Thread({
                try {
                    socket.use {
                        check(ReceiverSocketSetup.bindThenProtect(it, { socket -> ReceiverLink(this, host).bind(socket) }, { socket -> protect(socket) })) { "Could not protect sender socket (VPN permission revoked or protection denied)" }
                        it.soTimeout = 10_000
                        it.connect(InetSocketAddress(host, port), 10_000)
                        Socks5.authenticateClient(DataInputStream(it.getInputStream()), it.getOutputStream(), password)
                    }
                    main.post { if (generation == token) { probe = null; startTunnel(host, port, password) } }
                } catch (e: Exception) { main.post { if (generation == token) fail("Sender check failed: ${e.message}") } }
            }, "receiver-check").apply { isDaemon = true; start() }
        } catch (e: Exception) { fail(e.message ?: "Receiver could not start") }
        return START_NOT_STICKY
    }

    private fun startTunnel(host: String, port: Int, password: String) {
        try {
            val descriptor = Builder().setSession("DataNet Share · experimental receiver")
                .addAddress(Constants.VPN_ADDRESS, 24).addRoute("0.0.0.0", 0)
                .addDnsServer(Constants.TUNNELED_DNS).setMtu(1500)
                .setBlocking(false).establish() ?: error("VPN permission was revoked")
            pfd = descriptor
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataNetShare::Receiver").apply { acquire() }
            val token = generation
            val link = ReceiverLink(this, host)
            bridge = Tun2Socks(descriptor.fileDescriptor, host, port, password,
                { socket -> ReceiverSocketSetup.bindThenProtect(socket, { link.bind(it) }, { protect(it) }) },
                { socket -> ReceiverSocketSetup.bindThenProtect(socket, { link.bind(it) }, { protect(it) }) },
                onFailure = { message -> main.post { if (generation == token) fail(message) } },
                onStats = { active, rx, tx ->
                    if (generation == token) {
                        val recent = recentTcpError
                        SessionStore.stats.postValue("$active TCP flow(s) · ↓ ${rx / 1024} KB · ↑ ${tx / 1024} KB" +
                            if (recent.isNullOrEmpty()) "" else "\nLast TCP error: $recent")
                    }
                },
                onTcpError = { error -> if (generation == token) recentTcpError = error })
                .also { it.start() }
            SessionStore.publish(SessionStore.State(SessionStore.Mode.RECEIVER, "active", "Experimental IPv4 VPN via $host:$port", host))
            getSystemService(NotificationManager::class.java).notify(Constants.RECEIVER_NOTIFICATION_ID, notification("IPv4 tunnel via $host:$port"))
        } catch (e: Exception) { fail("VPN could not start: ${e.message}") }
    }

    private fun notification(text: String) = ServiceNotifications.build(this, "DataNet Share · Receiver", text,
        Intent(this, ReceiverVpnService::class.java).setAction(ACTION_STOP))
    private fun fail(message: String) {
        cleanup()
        SessionStore.publish(SessionStore.State(SessionStore.Mode.RECEIVER, "failed", message)); stopSelf()
    }
    private fun cleanup() {
        ++generation
        try { probe?.close() } catch (_: Exception) {}; probe = null
        bridge?.stop(); bridge = null
        try { pfd?.close() } catch (_: Exception) {}; pfd = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { cleanup(); if (SessionStore.current.status != "failed") SessionStore.stopped(SessionStore.Mode.RECEIVER); super.onDestroy() }
    override fun onRevoke() { fail("Android revoked this VPN connection") }
}

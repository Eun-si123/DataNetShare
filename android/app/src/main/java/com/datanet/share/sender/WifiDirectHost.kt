package com.datanet.share.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** Cancels stale callbacks and only removes the group created by this session. */
class WifiDirectHost(private val context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var generation = 0
    private var running = false
    private var ownsGroup = false
    private var ready = false
    private var registered = false
    private val mutableState = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = mutableState
    sealed class State {
        object Idle : State()
        data class Initializing(val attempt: Int, val maxAttempts: Int) : State()
        data class CreatingGroup(val attempt: Int, val maxAttempts: Int) : State()
        data class Retrying(val attempt: Int, val maxAttempts: Int, val nextDelayMs: Long, val reason: String) : State()
        data class GroupReady(val groupOwnerIp: String, val ssid: String, val passphrase: String) : State()
        data class Failed(val reason: String, val hint: String = "") : State()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!running) return
            if (intent?.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, 0) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                mutableState.value = State.Failed("Wi-Fi Direct is disabled", "Turn Wi-Fi on and start again.")
            } else if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) query(generation)
        }
    }
    fun start() {
        if (running) return
        val mgr = manager ?: run { mutableState.value = State.Failed("Wi-Fi Direct is not supported"); return }
        if (context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true) {
            mutableState.value = State.Failed("Wi-Fi is off", "Enable Wi-Fi in Android settings."); return
        }
        running = true; val token = ++generation
        try {
            channel = mgr.initialize(context, context.mainLooper) {
                if (running && token == generation) mutableState.value = State.Failed("Wi-Fi Direct channel disconnected")
            }
            val filter = IntentFilter().apply { addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
            registered = true
            create(token, 1)
            handler.postDelayed({
                if (running && token == generation && !ready) mutableState.value = State.Failed("Wi-Fi Direct setup timed out", "On Android 8–12, enable Location services. Also check Wi-Fi and nearby-device permissions.")
            }, 25_000)
        } catch (e: Exception) { mutableState.value = State.Failed("Wi-Fi Direct: ${e.message}") }
    }
    private fun create(token: Int, attempt: Int) {
        if (!running || generation != token) return
        mutableState.value = State.CreatingGroup(attempt, 4)
        try {
            manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!running || generation != token) return
                    ownsGroup = true; query(token)
                }
                override fun onFailure(reason: Int) {
                    if (!running || generation != token) return
                    val name = when (reason) { WifiP2pManager.BUSY -> "BUSY"; WifiP2pManager.P2P_UNSUPPORTED -> "Unsupported"; else -> "Error $reason" }
                    if (attempt < 4 && reason == WifiP2pManager.BUSY) {
                        val delay = 1000L shl (attempt - 1)
                        mutableState.value = State.Retrying(attempt, 4, delay, name)
                        handler.postDelayed({ create(token, attempt + 1) }, delay)
                    } else mutableState.value = State.Failed("Wi-Fi Direct: $name", "Another app or an existing group may be using Wi-Fi Direct. Stop it before retrying.")
                }
            })
        } catch (e: SecurityException) { mutableState.value = State.Failed("Wi-Fi Direct permission missing", e.message ?: "") }
    }
    private fun query(token: Int) {
        if (!running || generation != token) return
        try { manager?.requestConnectionInfo(channel) { info -> onInfo(token, info) } }
        catch (e: SecurityException) { mutableState.value = State.Failed("Cannot read Wi-Fi Direct connection", e.message ?: "") }
    }
    private fun onInfo(token: Int, info: WifiP2pInfo?) {
        if (!running || generation != token) return
        if (info?.groupFormed != true) {
            if (ready) mutableState.value = State.Failed("Wi-Fi Direct group disconnected")
            return
        }
        if (!ownsGroup) return // Ignore events from a pre-existing group owned by another session.
        if (!info.isGroupOwner) { mutableState.value = State.Failed("This phone is not the group owner"); return }
        val ip = info.groupOwnerAddress?.hostAddress ?: return
        try {
            manager?.requestGroupInfo(channel) { group ->
                if (!running || token != generation) return@requestGroupInfo
                if (group == null || group.passphrase.isNullOrEmpty()) {
                    if (!ready) handler.postDelayed({ query(token) }, 500)
                    return@requestGroupInfo
                }
                ready = true; mutableState.value = State.GroupReady(ip, group.networkName ?: "", group.passphrase)
            }
        } catch (e: SecurityException) { mutableState.value = State.Failed("Cannot read Wi-Fi Direct group", e.message ?: "") }
    }
    fun stop() {
        running = false; ++generation; handler.removeCallbacksAndMessages(null)
        if (registered) { try { context.unregisterReceiver(receiver) } catch (_: Exception) {}; registered = false }
        val old = channel; channel = null
        fun closeChannel() { if (Build.VERSION.SDK_INT >= 27) old?.close() }
        if (ownsGroup && old != null) {
            try { manager?.removeGroup(old, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { closeChannel() }; override fun onFailure(reason: Int) { closeChannel() }
            }) } catch (_: Exception) { closeChannel() }
        } else closeChannel()
        ownsGroup = false; ready = false; mutableState.value = State.Idle
    }
}

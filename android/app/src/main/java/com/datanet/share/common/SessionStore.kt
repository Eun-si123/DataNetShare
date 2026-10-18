package com.datanet.share.common

import androidx.lifecycle.MutableLiveData
import com.datanet.share.sender.PeerUsageLedger

/** In-process state survives activity recreation without starting services just to query them. */
object SessionStore {
    enum class Mode { NONE, SENDER, RECEIVER }
    data class State(
        val mode: Mode = Mode.NONE,
        val status: String = "stopped",
        val details: String = "Ready",
        val proxyHost: String = "",
        val proxyPassword: String = "",
        val ssid: String = "",
        val wifiPassword: String = ""
    ) {
        val busy: Boolean get() = status == "starting" || status == "active"
    }
    val state = MutableLiveData(State())
    val stats = MutableLiveData("")
    /** UI-only local per-IP usage. Never copied into sanitized diagnostics. */
    val peerUsage = MutableLiveData<List<PeerUsageLedger.Snapshot>>(emptyList())
    // Strictly aggregate counters only. Used by the safe diagnostic copy action.
    val telemetry = MutableLiveData("")
    private val diagnosticJournal = DiagnosticJournal()
    val diagnostics = MutableLiveData(diagnosticJournal.snapshot())
    fun note(kind: NetworkEvent) { diagnostics.postValue(diagnosticJournal.record(kind)) }
    fun resetDiagnostics() { diagnostics.postValue(diagnosticJournal.clear()) }
    @Volatile var current = State(); private set

    @Synchronized fun publish(value: State) { current = value; state.postValue(value) }
    @Synchronized fun claim(mode: Mode): Boolean {
        if (current.busy) return false
        resetDiagnostics(); telemetry.postValue(""); peerUsage.postValue(emptyList())
        publish(State(mode, "starting", "Starting…")); stats.postValue(""); return true
    }
    fun stopped(mode: Mode) {
        if (current.mode == mode) {
            publish(State()); stats.postValue(""); telemetry.postValue(""); peerUsage.postValue(emptyList())
        }
    }
}

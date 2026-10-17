package com.datanet.share

import android.content.Intent
import androidx.lifecycle.ViewModel

/**
 * Keeps sensitive pairing details in memory across Activity rotation and system
 * permission dialogs. Never put the SOCKS/Wi-Fi secrets in SharedPreferences,
 * a saved-instance Bundle, analytics, or a diagnostic message.
 */
class ConnectionFormState : ViewModel() {
    var pendingReceiver: Intent? = null
    var receiverPassword: String = ""
    var wifiSsid: String = ""
    var wifiPassword: String = ""
    var showQr: Boolean = false
    // Explicit P2P Wi-Fi passphrase is memory-only; do not save in preferences.
    var customP2pPassword: String = ""
    var p2pSuffix: String = ""
    // Non-secret disclosure state follows Activity across rotation only.
    var showAdvancedSettings: Boolean = false
    var showManualSettings: Boolean = false

    fun clearImportedWifi() {
        wifiSsid = ""
        wifiPassword = ""
    }
}

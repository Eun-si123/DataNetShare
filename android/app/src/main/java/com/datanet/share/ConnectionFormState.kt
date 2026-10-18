package com.datanet.share

import android.content.Intent
import androidx.lifecycle.ViewModel

/**
 * Keeps sensitive pairing details in memory across Activity rotation and system
 * permission dialogs. Never put SOCKS secrets or plaintext Wi-Fi passwords in SharedPreferences,
 * a saved-instance Bundle, analytics, or a diagnostic message.
 */
class ConnectionFormState : ViewModel() {
    var pendingReceiver: Intent? = null
    var receiverPassword: String = ""
    var wifiSsid: String = ""
    var wifiPassword: String = ""
    var showQr: Boolean = false
    // Plaintext exists only in memory; an opt-in custom password is encrypted at rest.
    var customP2pPassword: String = ""
    var customProxyPassword: String = ""
    var pendingAutoReceiver: Boolean = false
    var customP2pPasswordInitialized: Boolean = false
    var p2pSuffix: String = ""
    // Non-secret disclosure state follows Activity across rotation only.
    var showAdvancedSettings: Boolean = false
    var showManualSettings: Boolean = false

    fun clearImportedWifi() {
        wifiSsid = ""
        wifiPassword = ""
    }
}

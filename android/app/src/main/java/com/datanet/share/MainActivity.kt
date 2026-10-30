package com.datanet.share

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.pm.PackageManager
import android.net.VpnService
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.datanet.share.common.Endpoint
import com.datanet.share.common.InterfaceEnumerator
import com.datanet.share.common.PairingPayload
import com.datanet.share.common.PeerWifiConnector
import com.datanet.share.common.ProxyPasswordPolicy
import com.datanet.share.common.SessionStore
import com.datanet.share.common.UdpTest
import com.datanet.share.common.WifiPasswordVault
import com.datanet.share.receiver.ReceiverVpnService
import com.datanet.share.sender.PeerUsageLedger
import com.datanet.share.sender.SenderService
import com.datanet.share.sender.WifiDirectProfile

class MainActivity : AppCompatActivity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var password: EditText
    private lateinit var addresses: Spinner
    private lateinit var defaultRoute: Switch
    private lateinit var wifiFallback: Switch
    private lateinit var receiverFallback: Switch
    private lateinit var customProxyPassword: EditText
    private lateinit var qrAutoJoin: Switch
    private lateinit var p2pNameSuffix: EditText
    private lateinit var p2pWifiPassword: EditText
    private lateinit var p2pBand: Spinner
    private var candidates = emptyList<InterfaceEnumerator.InterfaceInfo>()
    // Activity-scoped ViewModel survives rotation; credentials are never written to disk.
    private val form by lazy { ViewModelProvider(this)[ConnectionFormState::class.java] }
    private val preferences by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private val p2pPasswordVault by lazy { WifiPasswordVault(this) }
    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (grants[required] == true) startSender(true) else showMessage(getString(R.string.permission_required))
    }
    private val receiverWifiPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && form.pendingAutoReceiver) requestPeerWifi()
        else { form.pendingAutoReceiver = false; showMessage(getString(R.string.qr_wifi_permission_denied)) }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        val pairing = PairingPayload.parse(raw)
        if (pairing == null) {
            showMessage(getString(R.string.qr_invalid))
        } else if (!SessionStore.current.busy) {
            host.setText(pairing.host)
            port.setText(pairing.port.toString())
            password.setText(pairing.password)
            form.wifiSsid = pairing.ssid
            form.wifiPassword = pairing.wifiPassword
            renderImportedWifi()
            if (pairing.ssid.isNotEmpty() && qrAutoJoin.isChecked) {
                form.pendingAutoReceiver = true
                requestPeerWifi()
            } else showMessage(getString(R.string.qr_imported))
        }
    }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = form.pendingReceiver; form.pendingReceiver = null
        if (result.resultCode == RESULT_OK && intent != null) launchService(intent)
        else showMessage(getString(R.string.vpn_denied))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        host = findViewById(R.id.receiverHost); port = findViewById(R.id.receiverPort)
        password = findViewById(R.id.receiverPassword); addresses = findViewById(R.id.bindAddress)
        defaultRoute = findViewById(R.id.defaultRoute)
        wifiFallback = findViewById(R.id.wifiFallback)
        receiverFallback = findViewById(R.id.receiverFallback)
        customProxyPassword = findViewById(R.id.customProxyPassword)
        qrAutoJoin = findViewById(R.id.qrAutoJoin)
        customProxyPassword.setText(form.customProxyPassword)
        customProxyPassword.doAfterTextChanged { form.customProxyPassword = it?.toString().orEmpty() }
        customProxyPassword.isSaveEnabled = false
        wifiFallback.isChecked = preferences.getBoolean("wifi_fallback", false)
        receiverFallback.isChecked = preferences.getBoolean("receiver_fallback", false)
        qrAutoJoin.isChecked = preferences.getBoolean("qr_auto_join", true)
        receiverFallback.setOnCheckedChangeListener { _, enabled -> preferences.edit().putBoolean("receiver_fallback", enabled).apply() }
        qrAutoJoin.setOnCheckedChangeListener { _, enabled -> preferences.edit().putBoolean("qr_auto_join", enabled).apply() }
        wifiFallback.setOnCheckedChangeListener { _, enabled -> preferences.edit().putBoolean("wifi_fallback", enabled).apply() }
        defaultRoute.setOnCheckedChangeListener { _, enabled -> wifiFallback.isEnabled = !enabled }
        p2pNameSuffix = findViewById(R.id.p2pNameSuffix)
        p2pWifiPassword = findViewById(R.id.p2pCustomPassword)
        p2pBand = findViewById(R.id.p2pBand)
        p2pBand.adapter = ArrayAdapter(this, R.layout.spinner_selection, listOf(
            getString(R.string.p2p_band_auto),
            getString(R.string.p2p_band_24),
            getString(R.string.p2p_band_5)
        )).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val requestedBand = preferences.getString("p2p_band", WifiDirectProfile.Band.AUTO.name)
        val bands = WifiDirectProfile.Band.entries
        p2pBand.setSelection(bands.indexOfFirst { it.name == requestedBand }.coerceAtLeast(0))
        p2pNameSuffix.setText(form.p2pSuffix.ifEmpty { preferences.getString("p2p_suffix", "").orEmpty() })
        p2pNameSuffix.doAfterTextChanged { form.p2pSuffix = it?.toString().orEmpty() }
        if (!form.customP2pPasswordInitialized) {
            try {
                form.customP2pPassword = p2pPasswordVault.load().orEmpty()
            } catch (_: Exception) {
                // Do not log ciphertext, recovered password or platform crypto details.
                showMessage(getString(R.string.p2p_password_restore_failed))
                try { p2pPasswordVault.clear() } catch (_: Exception) { /* user can retry */ }
                form.customP2pPassword = ""
            }
            form.customP2pPasswordInitialized = true
        }
        p2pWifiPassword.setText(form.customP2pPassword)
        p2pWifiPassword.doAfterTextChanged { form.customP2pPassword = it?.toString().orEmpty() }
        val olderAndroid = Build.VERSION.SDK_INT < 29
        p2pNameSuffix.isEnabled = !olderAndroid
        p2pWifiPassword.isEnabled = !olderAndroid
        p2pBand.isEnabled = !olderAndroid
        findViewById<View>(R.id.p2pUnsupportedNotice).visibility =
            if (olderAndroid) View.VISIBLE else View.GONE
        // The role and transport are non-secret user preferences. Force the
        // running service's role when Activity is recreated.
        val roleGroup = findViewById<RadioGroup>(R.id.roleGroup)
        val transportGroup = findViewById<RadioGroup>(R.id.transportGroup)
        roleGroup.check(if (SessionStore.current.busy) {
            if (SessionStore.current.mode == SessionStore.Mode.RECEIVER) R.id.roleReceive else R.id.roleShare
        } else if (preferences.getBoolean("role_share", true)) R.id.roleShare else R.id.roleReceive)
        transportGroup.check(if (preferences.getBoolean("share_direct", true)) R.id.transportDirect else R.id.transportLocal)
        // Deliberately not in savedInstanceState or SharedPreferences.
        password.setText(form.receiverPassword)
        password.doAfterTextChanged { form.receiverPassword = it?.toString() ?: "" }
        renderImportedWifi()
        defaultRoute.isChecked = preferences.getBoolean("default_route", false)
        wifiFallback.isEnabled = !defaultRoute.isChecked
        if (savedInstanceState == null) {
            host.setText(preferences.getString("host", "192.168.49.1"))
            port.setText(preferences.getString("port", "8282"))
        }
        refreshAddresses()
        roleGroup.setOnCheckedChangeListener { _, checked ->
            if (!SessionStore.current.busy) {
                preferences.edit().putBoolean("role_share", checked == R.id.roleShare).apply()
                render(SessionStore.current)
            }
        }
        transportGroup.setOnCheckedChangeListener { _, checked ->
            if (!SessionStore.current.busy) {
                preferences.edit().putBoolean("share_direct", checked == R.id.transportDirect).apply()
                render(SessionStore.current)
            }
        }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val current = SessionStore.current
            val role = if (roleGroup.checkedRadioButtonId == R.id.roleReceive)
                ConnectionRole.RECEIVE else ConnectionRole.SHARE
            val link = if (transportGroup.checkedRadioButtonId == R.id.transportLocal)
                SharingLink.LOCAL else SharingLink.WIFI_DIRECT
            when (ConnectionControls.action(current.busy, role, link)) {
                ConnectionAction.START_DIRECT -> startSender(true)
                ConnectionAction.START_LOCAL -> startSender(false)
                ConnectionAction.START_RECEIVER -> startReceiver()
                ConnectionAction.STOP -> {
                    // Match notification STOP: let the active foreground
                    // service execute its explicit, idempotent cleanup path.
                    // In particular, the receiver must close its TUN fd and
                    // revoke the app's VPN routing, not just hide the UI.
                    when (current.mode) {
                        SessionStore.Mode.SENDER -> stopRunningService(
                            Intent(this, SenderService::class.java).setAction(SenderService.ACTION_STOP))
                        SessionStore.Mode.RECEIVER -> stopRunningService(
                            Intent(this, ReceiverVpnService::class.java).setAction(ReceiverVpnService.ACTION_STOP))
                        SessionStore.Mode.NONE -> Unit
                    }
                }
            }
        }
        findViewById<Button>(R.id.btnAdvanced).setOnClickListener {
            form.showAdvancedSettings = !form.showAdvancedSettings
            render(SessionStore.current)
        }
        findViewById<Button>(R.id.btnManual).setOnClickListener {
            form.showManualSettings = !form.showManualSettings
            render(SessionStore.current)
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshAddresses() }
        findViewById<Button>(R.id.btnQr).setOnClickListener {
            if (SessionStore.current.mode == SessionStore.Mode.SENDER && SessionStore.current.status == "active") {
                form.showQr = !form.showQr
                render(SessionStore.current)
            }
        }
        findViewById<Button>(R.id.btnScanQr).setOnClickListener {
            if (!SessionStore.current.busy) {
                qrScanner.launch(ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setCaptureActivity(RotatableQrCaptureActivity::class.java)
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)
                    .setPrompt(getString(R.string.qr_scan_prompt)))
            }
        }
        findViewById<Button>(R.id.btnCopyWifi).setOnClickListener {
            val secret = form.wifiPassword
            if (secret.isNotEmpty()) {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Wi-Fi password", secret))
                showMessage(getString(R.string.copied))
            }
        }
        findViewById<Button>(R.id.btnWifiSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = findViewById<TextView>(R.id.credentialsText).text
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DataNet Share", text))
            showMessage(getString(R.string.copied))
        }
        findViewById<Button>(R.id.btnCopyDiagnostics).setOnClickListener {
            // Do not copy network endpoints, QR, Wi-Fi/SOCKS5 credentials or URLs.
            val snapshot = SessionStore.diagnostics.value.orEmpty()
            val telemetry = SessionStore.telemetry.value.orEmpty()
            val role = SessionStore.current.mode.name
            // Telemetry is an allow-listed aggregate string, never raw service state.
            val safeText = "DataNetShare Dev ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                "Role: $role\n" +
                (if (telemetry.isEmpty()) "" else "Performance:\n$telemetry\n") +
                "Events:\n$snapshot"
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("DataNetShare diagnostics", safeText))
            showMessage(getString(R.string.diagnostics_copied))
        }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener {
            val panel = findViewById<View>(R.id.diagnosticsPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnUdpTest).setOnClickListener {
            if (SessionStore.current.mode != SessionStore.Mode.RECEIVER || SessionStore.current.status != "active") {
                showMessage(getString(R.string.test_receiver_first)); return@setOnClickListener
            }
            it.isEnabled = false
            UdpTest.runTest({ message -> log(message) }, { message -> log(message) }, {
                runOnUiThread { if (!isDestroyed) it.isEnabled = true }
            })
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.versionText).text = getString(R.string.version_label, version)
        SessionStore.state.observe(this) { state -> render(state) }
        SessionStore.stats.observe(this) { text -> findViewById<TextView>(R.id.statsText).text = text }
        SessionStore.peerUsage.observe(this) { peers ->
            findViewById<TextView>(R.id.peerUsageText).text = if (peers.isEmpty()) {
                getString(R.string.peer_usage_empty)
            } else buildString {
                peers.forEach { peer ->
                    append(getString(R.string.peer_usage_row, peer.ip, peer.activeConnections,
                        com.datanet.share.common.FormatBytes.format(peer.txBytes),
                        com.datanet.share.common.FormatBytes.format(peer.rxBytes)))
                    append('\n')
                }
            }.trimEnd()
        }
        SessionStore.diagnostics.observe(this) { text -> findViewById<TextView>(R.id.diagnosticSummary).text = text }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun render(state: SessionStore.State) {
        findViewById<TextView>(R.id.statusText).setText(when (state.status) {
            "starting" -> R.string.starting; "active" -> R.string.active; "failed" -> R.string.failed; else -> R.string.idle
        })
        findViewById<TextView>(R.id.detailsText).text = state.details
        findViewById<View>(R.id.peerUsagePanel).visibility =
            if (state.mode == SessionStore.Mode.SENDER && state.status == "active")
                View.VISIBLE else View.GONE
        val roleGroup = findViewById<RadioGroup>(R.id.roleGroup)
        val transportGroup = findViewById<RadioGroup>(R.id.transportGroup)
        val active = state.busy
        if (active) {
            val requiredRole = if (state.mode == SessionStore.Mode.RECEIVER) R.id.roleReceive else R.id.roleShare
            if (roleGroup.checkedRadioButtonId != requiredRole) roleGroup.check(requiredRole)
        }
        val share = roleGroup.checkedRadioButtonId != R.id.roleReceive
        val direct = transportGroup.checkedRadioButtonId != R.id.transportLocal
        val selectionVisible = if (active) View.GONE else View.VISIBLE
        roleGroup.visibility = selectionVisible
        findViewById<View>(R.id.modeLabel).visibility = selectionVisible
        findViewById<View>(R.id.senderSettings).visibility = if (!active && share) View.VISIBLE else View.GONE
        findViewById<View>(R.id.receiverSettings).visibility = if (!active && !share) View.VISIBLE else View.GONE
        findViewById<View>(R.id.localSettings).visibility = if (!direct) View.VISIBLE else View.GONE
        findViewById<View>(R.id.wifiDirectOptions).visibility =
            if (share && direct && !active) View.VISIBLE else View.GONE
        findViewById<View>(R.id.advancedSettings).visibility =
            if (form.showAdvancedSettings) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnAdvanced).setText(
            if (form.showAdvancedSettings) R.string.advanced_hide else R.string.advanced_show)
        findViewById<View>(R.id.manualSettings).visibility =
            if (form.showManualSettings) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnManual).setText(
            if (form.showManualSettings) R.string.manual_hide else R.string.manual_show)
        val action = ConnectionControls.action(active,
            if (share) ConnectionRole.SHARE else ConnectionRole.RECEIVE,
            if (direct) SharingLink.WIFI_DIRECT else SharingLink.LOCAL)
        val connectButton = findViewById<Button>(R.id.btnConnect)
        connectButton.setText(when {
            active && state.status == "starting" -> R.string.cancel_connect
            action == ConnectionAction.STOP -> R.string.stop
            action == ConnectionAction.START_DIRECT -> R.string.start_p2p
            action == ConnectionAction.START_LOCAL -> R.string.start_local
            else -> R.string.start_receiver
        })
        connectButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor(
            if (active) "#B91C1C" else if (share) "#2563EB" else "#047857"))
        listOf(R.id.bindAddress, R.id.btnRefresh, R.id.defaultRoute, R.id.receiverHost,
            R.id.receiverPort, R.id.receiverPassword, R.id.btnScanQr)
            .forEach { findViewById<View>(it).isEnabled = !active }
        val customEnabled = !active && Build.VERSION.SDK_INT >= 29
        p2pNameSuffix.isEnabled = customEnabled
        p2pWifiPassword.isEnabled = customEnabled
        p2pBand.isEnabled = customEnabled
        customProxyPassword.isEnabled = !active
        wifiFallback.isEnabled = !active && !defaultRoute.isChecked
        receiverFallback.isEnabled = !active
        qrAutoJoin.isEnabled = !active
        val hasCredentials = state.mode == SessionStore.Mode.SENDER && state.status == "active"
        findViewById<View>(R.id.credsCard).visibility = if (hasCredentials) View.VISIBLE else View.GONE
        if (!hasCredentials) form.showQr = false
        val qrPanel = findViewById<View>(R.id.qrPanel)
        val showQr = hasCredentials && form.showQr
        qrPanel.visibility = if (showQr) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnQr).setText(if (showQr) R.string.hide_pairing_qr else R.string.show_pairing_qr)
        if (showQr) {
            try {
                val data = PairingPayload(state.proxyHost, 8282, state.proxyPassword, state.ssid, state.wifiPassword).encode()
                val bitmap = BarcodeEncoder().encodeBitmap(data, BarcodeFormat.QR_CODE, 600, 600)
                findViewById<ImageView>(R.id.qrImage).setImageBitmap(bitmap)
            } catch (_: Exception) {
                qrPanel.visibility = View.GONE
                form.showQr = false
                showMessage(getString(R.string.qr_failed))
            }
        } else {
            // Drop the sensitive QR bitmap when hidden or the sender stops.
            findViewById<ImageView>(R.id.qrImage).setImageDrawable(null)
        }
        findViewById<TextView>(R.id.credentialsText).text = if (hasCredentials) buildString {
            if (state.ssid.isNotEmpty()) append("SSID: ${state.ssid}\n${getString(R.string.wifi_password)}: ${state.wifiPassword}\n\n")
            append("SOCKS5: ${state.proxyHost}:8282\nUsername: datanet\n${getString(R.string.proxy_password)}: ${state.proxyPassword}")
        } else ""
    }
    private fun renderImportedWifi() {
        val hasWifi = form.wifiSsid.isNotEmpty()
        findViewById<View>(R.id.scannedWifiPanel).visibility = if (hasWifi) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.scannedWifiText).text = if (hasWifi) {
            getString(R.string.scanned_wifi_details, form.wifiSsid, form.wifiPassword)
        } else ""
        findViewById<TextView>(R.id.scanStatus).setText(
            if (form.receiverPassword.isNotEmpty()) R.string.qr_ready else R.string.qr_not_scanned)
    }

    private fun refreshAddresses() {
        val selected = candidates.getOrNull(addresses.selectedItemPosition)?.ip ?: preferences.getString("bind", "")
        candidates = InterfaceEnumerator.localCandidates()
        val labels = candidates.map { "${it.name}: ${it.ip}" }.ifEmpty { listOf(getString(R.string.no_addresses)) }
        addresses.adapter = ArrayAdapter(this, R.layout.spinner_selection, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        candidates.indexOfFirst { it.ip == selected }.takeIf { it >= 0 }?.let(addresses::setSelection)
    }
    private fun startSender(p2p: Boolean) {
        if (SessionStore.current.busy) return
        val profile = WifiDirectProfile(
            nameSuffix = p2pNameSuffix.text.toString().trim(),
            passphrase = p2pWifiPassword.text.toString(),
            band = WifiDirectProfile.Band.entries[p2pBand.selectedItemPosition.coerceIn(0, 2)]
        )
        if (p2p) {
            val problem = profile.validate()
            if (problem != null) {
                showMessage(getString(R.string.p2p_invalid_profile, problem)); return
            }
            if (Build.VERSION.SDK_INT < 29 && profile.isCustomized()) {
                showMessage(getString(R.string.p2p_android_10_required)); return
            }
            // Just like normal Android hotspot settings, remember the chosen
            // custom password by default. The AES key is held by Android Keystore;
            // only ciphertext is persisted in the app-private preferences.
            // Clearing the input switches back to generated passwords and
            // also forgets the previously saved password.
            try {
                if (profile.passphrase.isEmpty()) p2pPasswordVault.clear()
                else p2pPasswordVault.save(profile.passphrase)
            } catch (_: Exception) {
                showMessage(getString(R.string.p2p_password_save_failed))
                return
            }
            preferences.edit().putString("p2p_suffix", profile.nameSuffix)
                .putString("p2p_band", profile.band.name).apply()
        }
        val customProxy = customProxyPassword.text.toString()
        if (customProxy.isNotEmpty() && !ProxyPasswordPolicy.valid(customProxy)) {
            showMessage(getString(R.string.proxy_custom_invalid)); return
        }
        preferences.edit().putBoolean("role_share", true).putBoolean("share_direct", p2p).apply()
        if (p2p) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) { wifiPermission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(permission) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return }
        }
        val bind = candidates.getOrNull(addresses.selectedItemPosition)?.ip
        if (!p2p && bind == null) { showMessage(getString(R.string.pick_address)); return }
        preferences.edit().putBoolean("default_route", defaultRoute.isChecked).putString("bind", bind).apply()
        launchService(Intent(this, SenderService::class.java).apply {
            action = if (p2p) SenderService.ACTION_START else SenderService.ACTION_START_LOCAL
            putExtra(SenderService.EXTRA_BIND_HOST, bind); putExtra(SenderService.EXTRA_DEFAULT_ROUTE, defaultRoute.isChecked)
            putExtra(SenderService.EXTRA_WIFI_FALLBACK, wifiFallback.isChecked && !defaultRoute.isChecked)
            putExtra(SenderService.EXTRA_PROXY_PASSWORD, customProxy)
            if (p2p) {
                putExtra(SenderService.EXTRA_P2P_SUFFIX, profile.nameSuffix)
                putExtra(SenderService.EXTRA_P2P_PASSWORD, profile.passphrase)
                putExtra(SenderService.EXTRA_P2P_BAND, profile.band.name)
            }
        })
    }
    private fun startReceiver() {
        if (SessionStore.current.busy) return
        val address = host.text.toString().trim(); val proxyPort = port.text.toString().toIntOrNull()
        if (!Endpoint.isIpv4(address) || proxyPort == null || proxyPort !in 1..65535) { showMessage(getString(R.string.invalid_endpoint)); return }
        val proxyPassword = password.text.toString()
        if (proxyPassword.isEmpty()) { showMessage(getString(R.string.password_required)); return }
        preferences.edit().putString("host", address).putString("port", proxyPort.toString()).apply()
        val intent = Intent(this, ReceiverVpnService::class.java).apply {
            action = ReceiverVpnService.ACTION_START; putExtra(ReceiverVpnService.EXTRA_SOCKS_HOST, address)
            putExtra(ReceiverVpnService.EXTRA_SOCKS_PORT, proxyPort); putExtra(ReceiverVpnService.EXTRA_PASSWORD, proxyPassword)
            putExtra(ReceiverVpnService.EXTRA_RELEASE_PEER_ON_LOSS, receiverFallback.isChecked)
        }
        val permission = VpnService.prepare(this)
        if (permission == null) launchService(intent) else { form.pendingReceiver = intent; vpnPermission.launch(permission) }
    }
    private fun requestPeerWifi() {
        if (Build.VERSION.SDK_INT < 29) {
            form.pendingAutoReceiver = false
            showMessage(getString(R.string.qr_wifi_android10_required)); return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            receiverWifiPermission.launch(permission); return
        }
        try {
            PeerWifiConnector.request(this, form.wifiSsid, form.wifiPassword,
                host.text.toString().trim(), port.text.toString().toIntOrNull() ?: 0)
            showMessage(getString(R.string.qr_wifi_requesting))
        } catch (_: Exception) {
            form.pendingAutoReceiver = false
            showMessage(getString(R.string.qr_wifi_unavailable))
        }
    }
    private fun peerWifiChanged(status: PeerWifiConnector.Status) {
        when (status) {
            PeerWifiConnector.Status.CHECKING_SENDER -> if (form.pendingAutoReceiver) {
                showMessage(getString(R.string.qr_wifi_checking_sender))
            }
            PeerWifiConnector.Status.SENDER_UNREACHABLE -> if (form.pendingAutoReceiver) {
                form.pendingAutoReceiver = false
                PeerWifiConnector.release()
                showMessage(getString(R.string.qr_wifi_sender_unreachable))
            }
            PeerWifiConnector.Status.CONNECTED -> if (form.pendingAutoReceiver) {
                form.pendingAutoReceiver = false
                if (!SessionStore.current.busy) startReceiver()
            }
            PeerWifiConnector.Status.UNAVAILABLE, PeerWifiConnector.Status.LOST -> if (form.pendingAutoReceiver) {
                form.pendingAutoReceiver = false
                PeerWifiConnector.release()
                showMessage(getString(R.string.qr_wifi_unavailable))
            }
            else -> Unit
        }
    }
    override fun onResume() {
        super.onResume()
        PeerWifiConnector.setListener(::peerWifiChanged)
    }
    override fun onPause() {
        PeerWifiConnector.setListener(null)
        super.onPause()
    }
    override fun onDestroy() {
        if (isFinishing && !SessionStore.current.busy) PeerWifiConnector.release()
        super.onDestroy()
    }
    private fun stopRunningService(intent: Intent) {
        try {
            // The app is visible; the target service is already foregrounded.
            // Explicit STOP is the same command used by the notification action.
            startService(intent)
        } catch (e: Exception) {
            // Fallback if Android rejects the dispatch during a lifecycle race.
            stopService(Intent(intent).apply { action = null })
            showMessage(ServiceDispatchFailureMessages.stop(e))
        }
    }

    private fun launchService(intent: Intent) {
        try { ContextCompat.startForegroundService(this, intent) } catch (e: Exception) { showMessage(ServiceDispatchFailureMessages.start(e)) }
    }
    private fun showMessage(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun log(message: String) = runOnUiThread {
        if (!isDestroyed) {
            val view = findViewById<TextView>(R.id.logText)
            view.text = (view.text.toString() + message + "\n").takeLast(6000)
        }
    }
}

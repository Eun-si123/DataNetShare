package com.datanet.share

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import com.datanet.share.common.SessionStore
import com.datanet.share.common.UdpTest
import com.datanet.share.receiver.ReceiverVpnService
import com.datanet.share.sender.SenderService

class MainActivity : AppCompatActivity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var password: EditText
    private lateinit var addresses: Spinner
    private lateinit var defaultRoute: Switch
    private var candidates = emptyList<InterfaceEnumerator.InterfaceInfo>()
    // Activity-scoped ViewModel survives rotation; credentials are never written to disk.
    private val form by lazy { ViewModelProvider(this)[ConnectionFormState::class.java] }
    private val preferences by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (grants[required] == true) startSender(true) else showMessage(getString(R.string.permission_required))
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
            showMessage(getString(R.string.qr_imported))
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
        // Deliberately not in savedInstanceState or SharedPreferences.
        password.setText(form.receiverPassword)
        password.doAfterTextChanged { form.receiverPassword = it?.toString() ?: "" }
        renderImportedWifi()
        if (savedInstanceState == null) {
            host.setText(preferences.getString("host", "192.168.49.1"))
            port.setText(preferences.getString("port", "8282"))
            defaultRoute.isChecked = preferences.getBoolean("default_route", false)
        }
        refreshAddresses()
        findViewById<Button>(R.id.btnSender).setOnClickListener { startSender(true) }
        findViewById<Button>(R.id.btnLocal).setOnClickListener { startSender(false) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshAddresses() }
        findViewById<Button>(R.id.btnReceiver).setOnClickListener { startReceiver() }
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
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            // stopService does not create a new foreground service just to stop it.
            stopService(Intent(this, SenderService::class.java))
            stopService(Intent(this, ReceiverVpnService::class.java))
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = findViewById<TextView>(R.id.credentialsText).text
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DataNet Share", text))
            showMessage(getString(R.string.copied))
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
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun render(state: SessionStore.State) {
        findViewById<TextView>(R.id.statusText).setText(when (state.status) {
            "starting" -> R.string.starting; "active" -> R.string.active; "failed" -> R.string.failed; else -> R.string.idle
        })
        findViewById<TextView>(R.id.detailsText).text = state.details
        listOf(R.id.btnSender, R.id.btnLocal, R.id.btnReceiver, R.id.bindAddress, R.id.defaultRoute, R.id.receiverHost, R.id.receiverPort, R.id.receiverPassword)
            .forEach { findViewById<View>(it).isEnabled = !state.busy }
        findViewById<View>(R.id.btnScanQr).isEnabled = !state.busy
        findViewById<View>(R.id.btnStop).isEnabled = state.busy
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
        }
        val permission = VpnService.prepare(this)
        if (permission == null) launchService(intent) else { form.pendingReceiver = intent; vpnPermission.launch(permission) }
    }
    private fun launchService(intent: Intent) {
        try { ContextCompat.startForegroundService(this, intent) } catch (e: Exception) { showMessage(e.message ?: "Could not start service") }
    }
    private fun showMessage(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun log(message: String) = runOnUiThread {
        if (!isDestroyed) {
            val view = findViewById<TextView>(R.id.logText)
            view.text = (view.text.toString() + message + "\n").takeLast(6000)
        }
    }
}

package com.datanet.share.common

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * App-private encrypted storage for an explicitly configured Wi-Fi Direct
 * password, using a non-exportable Android Keystore AES key. The SOCKS5
 * per-session secret is never stored here.
 *
 * No plaintext fallback: if Keystore becomes unavailable after an OS reset
 * or app reinstall the UI must ask for the password again.
 */
class WifiPasswordVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "wifi_direct_password_v1", Context.MODE_PRIVATE
    )

    private companion object {
        const val ALIAS = "DataNetShare.wifiDirect.passphrase.v1"
        const val RECORD = "encrypted_wifi_password"
    }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    @Synchronized
    fun save(passphrase: String) {
        val encrypted = P2pPasswordCipher.encrypt(passphrase, encryptionKey())
        check(prefs.edit().putString(RECORD, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Encrypted Wi-Fi password could not be stored"
        }
    }

    @Synchronized
    fun load(): String? {
        val text = prefs.getString(RECORD, null) ?: return null
        val packed = Base64.decode(text, Base64.NO_WRAP)
        return P2pPasswordCipher.decrypt(packed, encryptionKey())
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(RECORD).commit()) { "Encrypted Wi-Fi password could not be cleared" }
    }
}

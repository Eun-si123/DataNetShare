package com.datanet.share.common

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pure-JVM testable AES-GCM envelope shared with Android Keystore-backed storage. */
internal object P2pPasswordCipher {
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(secret: String, key: SecretKey): ByteArray {
        require(isValid(secret)) { "Invalid Wi-Fi Direct passphrase" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == IV_SIZE) { "Unexpected GCM IV length" }
        return cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.US_ASCII))
    }

    fun decrypt(envelope: ByteArray, key: SecretKey): String {
        require(envelope.size in (IV_SIZE + TAG_BITS / 8)..(IV_SIZE + TAG_BITS / 8 + 63)) {
            "Invalid encrypted Wi-Fi password record"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(TAG_BITS, envelope.copyOfRange(0, IV_SIZE)))
        val secret = String(cipher.doFinal(envelope.copyOfRange(IV_SIZE, envelope.size)),
            Charsets.US_ASCII)
        require(isValid(secret)) { "Invalid decrypted Wi-Fi password" }
        return secret
    }

    private fun isValid(secret: String) =
        secret.length in 8..63 && secret.all { it.code in 32..126 }
}

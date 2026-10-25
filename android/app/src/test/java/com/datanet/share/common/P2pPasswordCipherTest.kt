package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException

class P2pPasswordCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun encryptAndDecryptLongTermCustomPassword() {
        val k = key()
        val secret = "A-stable-Password_2026!"
        val encrypted = P2pPasswordCipher.encrypt(secret, k)
        assertEquals(secret, P2pPasswordCipher.decrypt(encrypted, k))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(secret))
    }
    @Test fun identicalPasswordsProduceDifferentEncryptedBytes() {
        val k = key()
        val first = P2pPasswordCipher.encrypt("Stable!passphrase", k)
        val second = P2pPasswordCipher.encrypt("Stable!passphrase", k)
        assertFalse(first.contentEquals(second)) // randomized 96-bit IV
        assertEquals("Stable!passphrase", P2pPasswordCipher.decrypt(second, k))
    }
    @Test fun tamperedCiphertextCannotDecrypt() {
        val k = key()
        val data = P2pPasswordCipher.encrypt("StoredP2pPass99", k)
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        try {
            P2pPasswordCipher.decrypt(data, k)
            fail("Tampering must fail authenticated decryption")
        } catch (_: AEADBadTagException) {}
    }
    @Test fun wrongKeyCannotRestorePassword() {
        val data = P2pPasswordCipher.encrypt("StoredP2pPass99", key())
        try {
            P2pPasswordCipher.decrypt(data, key())
            fail("Foreign key cannot decrypt")
        } catch (_: AEADBadTagException) {}
    }
    @Test fun rejectMalformedAndInvalidPasswords() {
        for (bad in listOf("", "123", "\nabcdefgh", "한글비밀번호", "x".repeat(64))) {
            try {
                P2pPasswordCipher.encrypt(bad, key())
                fail("Invalid P2P password accepted")
            } catch (_: IllegalArgumentException) {}
        }
        try {
            P2pPasswordCipher.decrypt(ByteArray(8), key())
            fail("Too short encrypted record accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun passphraseBoundaryLengthsRoundTrip() {
        val k = key()
        for (secret in listOf("a".repeat(8), "z".repeat(63))) {
            assertEquals(secret, P2pPasswordCipher.decrypt(P2pPasswordCipher.encrypt(secret, k), k))
        }
        try {
            P2pPasswordCipher.encrypt("x".repeat(7), k)
            fail("Seven-character passphrase must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun tamperedIvAndRecordLengthCannotBypassAuthentication() {
        val k = key()
        val original = P2pPasswordCipher.encrypt("ValidP2pPassword!", k)
        val tamperedIv = original.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        for (corrupted in listOf(tamperedIv, original.copyOf(original.size - 1),
                original + byteArrayOf(0))) {
            try {
                P2pPasswordCipher.decrypt(corrupted, k)
                fail("Modified GCM envelope must be rejected")
            } catch (_: AEADBadTagException) {}
        }
        for (invalidLength in listOf(27, 92)) {
            try {
                P2pPasswordCipher.decrypt(ByteArray(invalidLength), k)
                fail("Invalid encrypted record length must be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }
}

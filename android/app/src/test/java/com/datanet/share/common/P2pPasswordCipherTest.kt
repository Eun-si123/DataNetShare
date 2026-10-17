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
        assertFalse(encrypted.toList().containsAll(secret.toByteArray().toList()))
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
}

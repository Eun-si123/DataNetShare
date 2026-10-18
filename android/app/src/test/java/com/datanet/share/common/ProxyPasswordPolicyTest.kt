package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class ProxyPasswordPolicyTest {
    @Test fun allowsLongPrintableCustomSecret() {
        assertTrue(ProxyPasswordPolicy.valid("my-private-secret-2026"))
        assertTrue(ProxyPasswordPolicy.valid("A#b:12?something"))
        assertTrue(ProxyPasswordPolicy.valid("f".repeat(128)))
    }

    @Test fun refusesShortNonAsciiAndControls() {
        for (value in listOf("", "short", "spaces not allowed", "unicode비밀번호!!", "x".repeat(129), "a".repeat(12) + "\n")) {
            assertFalse(ProxyPasswordPolicy.valid(value))
        }
    }

    @Test fun versionedQrPreservesCustomPassword() {
        val pairing = PairingPayload("192.168.49.1", 8282, "CustomProxyPass-2026", "DIRECT-xyPhone", "WifiSecret-2026")
        assertEquals(pairing, PairingPayload.parse(pairing.encode()))
    }
}

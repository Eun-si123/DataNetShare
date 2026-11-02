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

    @Test fun asciiCredentialBoundaryRejectsSpaceControlsAndDel() {
        // The minimum-length password must reject every non-printable ASCII
        // code point, including space and DEL, not only newlines.
        for (code in 0..127) {
            val password = "a".repeat(11) + code.toChar()
            assertEquals("ASCII code=$code", code in 33..126, ProxyPasswordPolicy.valid(password))
        }
    }

    @Test fun credentialLengthBoundariesAndUnpairedSurrogate() {
        assertFalse(ProxyPasswordPolicy.valid("a".repeat(11)))
        assertTrue(ProxyPasswordPolicy.valid("a".repeat(12)))
        assertTrue(ProxyPasswordPolicy.valid("~".repeat(128)))
        assertFalse(ProxyPasswordPolicy.valid("~".repeat(129)))
        assertFalse(ProxyPasswordPolicy.valid("a".repeat(11) + '\uD800'))
    }
}

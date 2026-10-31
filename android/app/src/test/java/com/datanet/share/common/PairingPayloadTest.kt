package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class PairingPayloadTest {
    private val password = "0123456789abcdef0123456789abcdef"
    private val direct = PairingPayload(
        host = "192.168.49.1", port = 8282, password = password,
        ssid = "DIRECT-A1 My Phone", wifiPassword = "wifi+password;\\$"
    )

    @Test fun debugStringNeverExposesPairingSecretsOrPeerIdentifiers() {
        // A Kotlin data class normally prints every constructor field, including credentials.
        assertEquals("PairingPayload(<redacted>)", direct.toString())
        assertEquals("PairingPayload(<redacted>)", PairingPayload("192.168.42.10", 8282, password).toString())
        // Serialization remains intentional and separate from diagnostic formatting.
        assertEquals(direct, PairingPayload.parse(direct.encode()))
    }

    @Test fun wifiDirectRoundTripPreservesAllCredentials() {
        assertEquals(direct, PairingPayload.parse(direct.encode()))
        assertTrue(direct.encode().startsWith("datanetshare://pair/v1?"))
    }

    @Test fun localNetworkRoundTripWithoutWifiPassword() {
        val local = PairingPayload("192.168.42.10", 8282, password)
        assertEquals(local, PairingPayload.parse(local.encode()))
        assertFalse(local.encode().contains("&w="))
    }

    @Test fun rejectUnsupportedVersionAndOtherQrCodes() {
        val qr = direct.encode()
        assertNull(PairingPayload.parse(qr.replace("/v1?", "/v2?")))
        assertNull(PairingPayload.parse("WIFI:T:WPA;S:test;P:password;;"))
        assertNull(PairingPayload.parse("https://example.com/?token=secret"))
        assertNull(PairingPayload.parse(""))
        assertNull(PairingPayload.parse(qr + "#fragment"))
    }

    @Test fun rejectDuplicateOrUnknownFields() {
        val qr = direct.encode()
        assertNull(PairingPayload.parse(qr + "&pw=" + password))
        assertNull(PairingPayload.parse(qr + "&role=admin"))
        assertNull(PairingPayload.parse(qr.replace("&p=8282", "&p=8282&h=1.2.3.4")))
    }

    @Test fun rejectMalformedAndUnsafeValues() {
        val qr = direct.encode()
        assertNull(PairingPayload.parse(qr.replace("h=192.168.49.1", "h=0.0.0.0")))
        assertNull(PairingPayload.parse(qr.replace("p=8282", "p=0")))
        assertNull(PairingPayload.parse(qr.replace("pw=" + password, "pw=bad")))
        assertNull(PairingPayload.parse(qr + "&w=%GG"))
        assertNull(PairingPayload.parse(qr + "&w=%0A"))
        assertNull(PairingPayload.parse("x".repeat(1200)))
    }

    @Test fun rejectMalformedUtf8InsteadOfSilentlyReplacingSsidBytes() {
        val prefix = "datanetshare://pair/v1?h=192.168.49.1&p=8282&pw=$password&s="
        val suffix = "&w=password"
        for (invalid in listOf("%C3%28", "%E2%82", "%ED%A0%80", "%C0%AF")) {
            assertNull("Malformed UTF-8 must not become a different Wi-Fi SSID: $invalid",
                PairingPayload.parse(prefix + invalid + suffix))
        }
    }

    @Test fun validUtf8AndLiteralReplacementCharacterRemainAccepted() {
        val prefix = "datanetshare://pair/v1?h=192.168.49.1&p=8282&pw=$password&s="
        val suffix = "&w=password"
        assertEquals("한 €", PairingPayload.parse(prefix + "%ED%95%9C+%E2%82%AC" + suffix)?.ssid)
        // U+FFFD is valid when actually encoded as EF BF BD, not as a decoder fallback.
        assertEquals("\uFFFD", PairingPayload.parse(prefix + "%EF%BF%BD" + suffix)?.ssid)
    }

    @Test fun passwordsAndWifiInformationAreValidatedWhenEncoding() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload("192.168.49.1", 8282, "123").encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload("192.168.49.1", 8282, password, "DIRECT-FOO", "").encode()
        }
    }

    @Test fun encodingRejectsUnpairedUtf16WithoutChangingSsid() {
        // Java's URL encoder replaces malformed UTF-16 with '?', silently
        // changing the SSID on the receiving phone. Never emit such a QR.
        for (ssid in listOf("DIRECT-\uD800", "\uDC00-DIRECT", "a\uD800b", "\uD800\uD800")) {
            assertThrows(IllegalArgumentException::class.java) {
                direct.copy(ssid = ssid).encode()
            }
        }
        // Legitimate replacement characters and valid surrogate pairs remain valid.
        assertWifiAccepted("DIRECT-?", "password")
        assertWifiAccepted("DIRECT-\uFFFD", "password")
        assertWifiAccepted("DIRECT-\uD83D\uDE00", "password")
    }

    @Test fun wifiSsidLimitUsesUtf8BytesForEncodingAndParsing() {
        for (ssid in listOf("a", "a".repeat(32), "한".repeat(10) + "ab", "\ud83d\ude00".repeat(8))) {
            assertWifiAccepted(ssid, "password")
        }
        for (ssid in listOf("a".repeat(33), "한".repeat(11), "\ud83d\ude00".repeat(8) + "a")) {
            assertWifiRejected(ssid, "password")
        }
    }

    @Test fun wifiPassphraseBoundsMatchTheConnectorForEncodingAndParsing() {
        for (wifiPassword in listOf("a".repeat(8), "a".repeat(63), " ".repeat(8), "~".repeat(8))) {
            assertWifiAccepted("DIRECT-AB", wifiPassword)
        }
        for (wifiPassword in listOf("a".repeat(7), "a".repeat(64), "é".repeat(8), "\ud83d\ude00".repeat(4),
            "pass\u0000word", "pass\nword", "pass\u007fword")) {
            assertWifiRejected("DIRECT-AB", wifiPassword)
        }
    }

    @Test fun wifiDetailsRemainAnOptionalCompletePairWithoutControls() {
        val local = PairingPayload("192.168.49.1", 8282, password)
        assertEquals(local, PairingPayload.parse(local.encode()))
        assertFalse(local.encode().contains("&s="))
        assertFalse(local.encode().contains("&w="))
        assertWifiAccepted("", "")
        assertWifiRejected("", "password")
        assertWifiRejected("DIRECT-AB", "")
        assertWifiRejected("DIRECT-\nAB", "password")
        assertWifiRejected("DIRECT-\u0000AB", "password")
    }

    private fun assertWifiAccepted(ssid: String, wifiPassword: String) {
        val expected = direct.copy(ssid = ssid, wifiPassword = wifiPassword)
        assertEquals(expected, PairingPayload.parse(expected.encode()))
        assertEquals(expected, PairingPayload.parse(rawWifiPairing(ssid, wifiPassword)))
    }

    private fun assertWifiRejected(ssid: String, wifiPassword: String) {
        assertThrows(IllegalArgumentException::class.java) {
            direct.copy(ssid = ssid, wifiPassword = wifiPassword).encode()
        }
        // Build a scanned payload independently: an invalid encoder must not
        // prevent this test from reaching the parser's validation boundary.
        assertNull(PairingPayload.parse(rawWifiPairing(ssid, wifiPassword)))
    }

    private fun rawWifiPairing(ssid: String, wifiPassword: String): String =
        "datanetshare://pair/v1?h=192.168.49.1&p=8282&pw=$password" +
            "&s=" + URLEncoder.encode(ssid, "UTF-8") +
            "&w=" + URLEncoder.encode(wifiPassword, "UTF-8")
}

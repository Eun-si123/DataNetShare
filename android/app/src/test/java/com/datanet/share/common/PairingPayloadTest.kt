package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {
    private val password = "0123456789abcdef0123456789abcdef"
    private val direct = PairingPayload(
        host = "192.168.49.1", port = 8282, password = password,
        ssid = "DIRECT-A1 My Phone", wifiPassword = "wifi+password;\\$"
    )

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

    @Test fun passwordsAndWifiInformationAreValidatedWhenEncoding() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload("192.168.49.1", 8282, "123").encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload("192.168.49.1", 8282, password, "DIRECT-FOO", "").encode()
        }
    }
}

package com.datanet.share.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Query separators inside credentials or SSIDs must remain data, not new fields. */
class PairingPayloadQueryBoundaryTest {
    private val password = "A&pw=other+%#=Z123456"
    private val wifiPassword = "a&b=c+d%#ef"

    @Test fun reservedQueryCharactersRoundTripWithoutChangingCredentials() {
        val original = PairingPayload(
            "192.168.49.1", 8282, password,
            "DIRECT-X7&A=1", wifiPassword
        )
        val encoded = original.encode()
        assertEquals(original, PairingPayload.parse(encoded))
        assertEquals(1, Regex("&pw=").findAll(encoded).count())
        assertEquals(1, Regex("&w=").findAll(encoded).count())
        assertTrue(encoded.contains("%26"))
        assertTrue(encoded.contains("%3D"))
        assertTrue(encoded.contains("%2B"))
        assertTrue(encoded.contains("%25"))
        assertTrue(encoded.contains("%23"))
    }

    @Test fun encodedDuplicateFieldSyntaxCannotOverrideProxyPassword() {
        val raw = "datanetshare://pair/v1?h=192.168.49.1&p=8282&pw=abcdefghijkl" +
            "&s=DIRECT-X7%26pw%3Dmalicious&w=password"
        val parsed = PairingPayload.parse(raw)
        assertEquals("abcdefghijkl", parsed?.password)
        assertEquals("DIRECT-X7&pw=malicious", parsed?.ssid)
        assertEquals("password", parsed?.wifiPassword)
        assertNull(PairingPayload.parse(raw + "&pw=overridden"))
    }
}

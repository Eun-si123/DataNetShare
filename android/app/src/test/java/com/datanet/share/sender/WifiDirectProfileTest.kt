package com.datanet.share.sender

import com.datanet.share.common.PairingPayload
import org.junit.Assert.*
import org.junit.Test

class WifiDirectProfileTest {
    @Test fun defaultsKeepLegacyAndroidGeneratedGroup() {
        val profile = WifiDirectProfile()
        assertFalse(profile.isCustomized())
        assertNull(profile.validate())
    }
    @Test fun manualProfileProducesLegalNameWithinWifiSsidLimit() {
        val suffix = "A".repeat(22)
        val profile = WifiDirectProfile(suffix, "My-Private_Wifi#2026", WifiDirectProfile.Band.GHZ_5)
        assertNull(profile.validate())
        assertEquals("DIRECT-X7$suffix", profile.networkName("X7"))
        assertTrue(profile.networkName("X7").toByteArray(Charsets.UTF_8).size <= 32)
        assertTrue(profile.isCustomized())
    }
    @Test fun rejectMalformedSuffixAndTooLongNames() {
        for (suffix in listOf("A".repeat(23), "spaces not allowed", "한글", "a\nb", "x/y")) {
            assertNotNull("Must reject suffix: $suffix", WifiDirectProfile(nameSuffix = suffix).validate())
        }
    }
    @Test fun rejectWeakInvalidOrNonAsciiCustomPasswords() {
        for (secret in listOf("1234567", "x".repeat(64), "한글비밀번호", "a\nb12345678", "\u0000123456789")) {
            assertNotNull("Invalid password unexpectedly accepted", WifiDirectProfile(passphrase = secret).validate())
        }
        assertNull(WifiDirectProfile(passphrase = "CorrectHorseBattery").validate())
        assertNull(WifiDirectProfile(passphrase = "z".repeat(63)).validate())
    }
    @Test fun customWifiPasswordCanTravelInCurrentPairingQr() {
        val secret = "My-Private_Wifi#2026"
        val pairing = PairingPayload(
            host = "192.168.49.1", port = 8282,
            password = "0123456789abcdef0123456789abcdef",
            ssid = WifiDirectProfile(nameSuffix="MyPhone").networkName("G9"),
            wifiPassword = secret
        )
        assertEquals(pairing, PairingPayload.parse(pairing.encode()))
    }
    @Test fun requestedNetworkNameTagMustBeTwoAsciiAlnum() {
        for (tag in listOf("", "X", "XYZ", "X#", "한글")) {
            try {
                WifiDirectProfile().networkName(tag)
                fail("Invalid DIRECT tag accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}

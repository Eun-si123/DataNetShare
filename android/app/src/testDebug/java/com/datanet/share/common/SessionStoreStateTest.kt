package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class SessionStoreStateTest {
    @Test fun debugStringDoesNotRevealSenderSessionCredentialsOrIdentifiers() {
        val state = SessionStore.State(
            mode = SessionStore.Mode.SENDER,
            status = "active",
            details = "SOCKS5: 192.0.2.10:8282",
            proxyHost = "192.0.2.10",
            proxyPassword = "example-socks-password",
            ssid = "DIRECT-EXAMPLE",
            wifiPassword = "example-wifi-password"
        )
        assertEquals("State(<redacted>)", state.toString())
        // Redaction must not change the state consumed by the UI or copy/equality.
        assertEquals(state, state.copy())
        assertEquals("example-socks-password", state.copy().proxyPassword)
        val receiver = SessionStore.State(
            mode = SessionStore.Mode.RECEIVER,
            status = "active",
            details = "Experimental IPv4 VPN via 198.51.100.20:8282",
            proxyHost = "198.51.100.20"
        )
        assertEquals("State(<redacted>)", receiver.toString())
    }
}

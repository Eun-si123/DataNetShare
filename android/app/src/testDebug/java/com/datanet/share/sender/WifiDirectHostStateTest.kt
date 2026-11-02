package com.datanet.share.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDirectHostStateTest {
    @Test fun readyStateDebugStringRedactsCredentialsAndIdentifiers() {
        val ready = WifiDirectHost.State.GroupReady("192.0.2.10", "DIRECT-X7-TestDevice", "example-wifi-passphrase")
        assertEquals("GroupReady(<redacted>)", ready.toString())
        assertEquals(ready, ready.copy())
        assertEquals("example-wifi-passphrase", ready.passphrase)
    }
}

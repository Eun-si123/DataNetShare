package com.datanet.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionControlsTest {
    @Test fun defaultShareModeUsesDirectTransport() {
        assertEquals(ConnectionAction.START_DIRECT,
            ConnectionControls.action(false, ConnectionRole.SHARE, SharingLink.WIFI_DIRECT))
    }

    @Test fun localShareModeUsesExistingWifiOrUsbTransport() {
        assertEquals(ConnectionAction.START_LOCAL,
            ConnectionControls.action(false, ConnectionRole.SHARE, SharingLink.LOCAL))
    }

    @Test fun receiverModeStartsVpnIndependentOfSenderTransportSelection() {
        SharingLink.entries.forEach { link ->
            assertEquals(ConnectionAction.START_RECEIVER,
                ConnectionControls.action(false, ConnectionRole.RECEIVE, link))
        }
    }

    @Test fun activeOrStartingSessionAlwaysMapsToSameStopAction() {
        ConnectionRole.entries.forEach { role ->
            SharingLink.entries.forEach { link ->
                assertEquals(ConnectionAction.STOP, ConnectionControls.action(true, role, link))
            }
        }
    }
}

package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.IOException

class SenderProbeRetryPolicyTest {
    @Test fun retryOnlyLocalRouteFailureOnAppRequestedWifi() {
        val host = SocketException("EHOSTUNREACH (No route to host)")
        val network = SocketException("ENETUNREACH (Network is unreachable)")
        assertTrue(SenderProbeRetryPolicy.shouldRetry(host, 1, true))
        assertTrue(SenderProbeRetryPolicy.shouldRetry(network, 2, true))
        assertFalse(SenderProbeRetryPolicy.shouldRetry(host, 3, true))
        assertFalse(SenderProbeRetryPolicy.shouldRetry(host, 1, false))
    }

    @Test fun neverRetryBadAuthenticationOrPermissionOrGenericTimeout() {
        for (cause in listOf(
            IOException("SOCKS authentication failed"),
            IllegalStateException("Could not protect sender socket"),
            SocketTimeoutException("Read timed out"),
            SocketException("Connection refused")
        )) assertFalse(SenderProbeRetryPolicy.shouldRetry(cause, 1, true))
    }
}

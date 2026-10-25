package com.datanet.share.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Socks5ListenerFailureTest {
    @Test fun unexpectedListenerCloseReportsOnlySanitizedFailure() {
        val reported = AtomicReference<String>()
        val failureReceived = CountDownLatch(1)
        val proxy = Socks5Server(
            "127.0.0.1", 0, "test-only-password",
            object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket = error("No clients expected")
                override fun resolve(host: String): Array<InetAddress> = emptyArray()
                override fun datagram(): DatagramSocket = error("No clients expected")
            },
            onFailure = { message ->
                reported.set(message)
                failureReceived.countDown()
            }
        )
        proxy.start()
        try {
            // Closing the live listening socket without calling stop() forces
            // the actual accept-loop IOException path; no Android device needed.
            val field = Socks5Server::class.java.getDeclaredField("listener")
            field.isAccessible = true
            (field.get(proxy) as ServerSocket).close()
            assertTrue("unexpected accept failure was not reported",
                failureReceived.await(3, TimeUnit.SECONDS))
            assertEquals(SenderFailureMessages.LISTENER_STOPPED, reported.get())
            assertEquals("failed listener must be stopped", 0, proxy.boundPort)
        } finally {
            proxy.stop()
        }
    }
}

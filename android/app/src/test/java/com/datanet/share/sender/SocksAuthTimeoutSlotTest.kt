package com.datanet.share.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.TimeUnit

class SocksAuthTimeoutSlotTest {
    @Test fun incompleteGreetingTimesOutAndReleasesSingleClientSlot() =
        ProxyFixture(maxClients = 1).use { fixture ->
            Socket("127.0.0.1", fixture.proxy.boundPort).use { stalled ->
                stalled.soTimeout = 20_000
                stalled.getOutputStream().write(byteArrayOf(5))
                val start = System.nanoTime()
                // The server has a 15-second handshake read timeout. A genuine
                // EOF distinguishes timeout cleanup from merely refusing a new client.
                assertEquals("stalled handshake was not closed", -1, stalled.getInputStream().read())
                assertTrue("handshake did not wait for its read timeout",
                    System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(12))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var recovered = false
                while (!recovered && System.nanoTime() < deadline) {
                    try {
                        fixture.client().use { recovered = true }
                    } catch (_: java.io.IOException) {
                        Thread.sleep(25)
                    }
                }
                assertTrue("timed-out handshake did not release its connection permit", recovered)
            }
        }
}

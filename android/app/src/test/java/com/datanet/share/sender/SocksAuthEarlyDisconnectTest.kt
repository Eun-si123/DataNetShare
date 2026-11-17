package com.datanet.share.sender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.TimeUnit

class SocksAuthEarlyDisconnectTest {
    @Test fun disconnectDuringGreetingReleasesSingleClientSlot() =
        ProxyFixture(maxClients = 1).use { fixture ->
            repeat(3) {
                Socket("127.0.0.1", fixture.proxy.boundPort).use { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 1))
                    socket.getOutputStream().flush()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var reused = false
                while (!reused && System.nanoTime() < deadline) {
                    try {
                        fixture.client().use { reused = true }
                    } catch (_: java.io.IOException) {
                        Thread.sleep(25)
                    }
                }
                assertTrue("slot not reusable after partial greeting disconnect", reused)
            }
        }
}

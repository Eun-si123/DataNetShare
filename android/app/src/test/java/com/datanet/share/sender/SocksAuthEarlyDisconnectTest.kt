package com.datanet.share.sender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.TimeUnit

class SocksAuthEarlyDisconnectTest {
    @Test fun disconnectDuringPasswordPayloadReleasesSingleClientSlot() =
        ProxyFixture(maxClients = 1).use { fixture ->
            repeat(3) {
                Socket("127.0.0.1", fixture.proxy.boundPort).use { socket ->
                    socket.soTimeout = 1000
                    val output = socket.getOutputStream()
                    output.write(byteArrayOf(5, 1, 2))
                    output.flush()
                    val input = socket.getInputStream()
                    assertTrue("server did not select password authentication",
                        input.read() == 5 && input.read() == 2)
                    // RFC 1929 declares a 4-byte password but only 1 byte arrives.
                    output.write(byteArrayOf(1, 1, 'u'.code.toByte(), 4, 'x'.code.toByte()))
                    output.flush()
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
                assertTrue("slot not reusable after partial password disconnect", reused)
            }
        }

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

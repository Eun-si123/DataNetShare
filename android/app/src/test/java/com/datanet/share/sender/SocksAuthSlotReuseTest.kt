package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit

class SocksAuthSlotReuseTest {
    @Test fun rejectedAuthenticationEventuallyReleasesSingleClientSlot() =
        ProxyFixture(maxClients = 1).use { fixture ->
            repeat(3) {
                // Wait for both socket teardown and permit release. clientCount can
                // become zero just before the worker releases the permit.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var authenticated = false
                while (!authenticated && System.nanoTime() < deadline) {
                    Socket("127.0.0.1", fixture.proxy.boundPort).use { socket ->
                        socket.soTimeout = 1000
                        try {
                            Socks5.authenticateClient(DataInputStream(socket.getInputStream()),
                                socket.getOutputStream(), "synthetic-wrong-password")
                            fail("incorrect password was accepted")
                        } catch (_: IOException) { }
                    }
                    // A successful rejection may also be caused by admission denial.
                    // Prove the permit is available by authenticating correctly.
                    try {
                        fixture.client().use { authenticated = true }
                    } catch (_: IOException) {
                        Thread.sleep(25)
                    }
                }
                assertEquals("single-client slot was not reusable after failed auth", true, authenticated)
            }
        }
}

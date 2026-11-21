package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Verify the real server sends rejection before closing its control connection. */
class SocksAuthFailureCloseTest {
    @Test fun invalidCredentialsReceiveFailureThenEofAndReleaseSlot() =
        ProxyFixture(maxClients = 1).use { fixture ->
            Socket("127.0.0.1", fixture.proxy.boundPort).use { socket ->
                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                assertArrayEquals(byteArrayOf(5, 2), ByteArray(2).also(input::readFully))

                val user = Socks5.USERNAME.toByteArray(Charsets.UTF_8)
                val wrong = "wrong-test-password".toByteArray(Charsets.UTF_8)
                output.write(byteArrayOf(1, user.size.toByte()))
                output.write(user)
                output.write(wrong.size)
                output.write(wrong)
                output.flush()
                assertArrayEquals(byteArrayOf(1, 1), ByteArray(2).also(input::readFully))
                assertEquals("server must close after authentication failure", -1, input.read())
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
            assertEquals("authentication failure must release the sole slot", true, reused)
        }
}

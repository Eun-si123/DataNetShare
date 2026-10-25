package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class Socks5MalformedRequestReplyTest {
    @Test fun unsupportedAddressTypeReturnsSocksErrorWithoutBreakingListener() =
        ProxyFixture().use { fixture ->
            for (command in listOf(1, 3)) {
                fixture.client().use { client ->
                    client.getOutputStream().write(byteArrayOf(5, command.toByte(), 0, 0x7f))
                    val input = DataInputStream(client.getInputStream())
                    assertEquals(5, input.readUnsignedByte())
                    assertEquals(8, input.readUnsignedByte())
                    assertEquals(0, input.readUnsignedByte())
                    assertEquals("0.0.0.0", Socks5.readAddress(input).host)
                }
            }
            fixture.client().close()
        }

    @Test fun malformedHostnameReturnsGeneralFailureWithoutBreakingListener() =
        ProxyFixture().use { fixture ->
            fixture.client().use { client ->
                // Domain ATYP with a zero-length name is invalid, but framing is complete.
                client.getOutputStream().write(byteArrayOf(5, 1, 0, 3, 0, 0, 80))
                val input = DataInputStream(client.getInputStream())
                assertEquals(5, input.readUnsignedByte())
                assertEquals(1, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                assertEquals("0.0.0.0", Socks5.readAddress(input).host)
            }
            fixture.client().close()
        }

    @Test fun connectPortZeroFailsBeforeOpeningAnUpstreamSocket() {
        val upstreamAttempts = AtomicInteger()
        val upstream = object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket {
                upstreamAttempts.incrementAndGet()
                throw IllegalStateException("upstream must not receive port zero")
            }
            override fun resolve(host: String): Array<InetAddress> = throw AssertionError("unused")
            override fun datagram(): DatagramSocket = throw AssertionError("unused")
        }
        val proxy = Socks5Server("127.0.0.1", 0, "test-pass", upstream)
        proxy.start()
        try {
            Socket("127.0.0.1", proxy.boundPort).use { client ->
                client.soTimeout = 3000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                Socks5.authenticateClient(input, output, "test-pass")
                try {
                    Socks5.request(input, output, 1, Socks5.Address("127.0.0.1", 0))
                    fail("CONNECT to TCP port zero must be rejected")
                } catch (e: IOException) {
                    assertTrue("expected SOCKS general failure, not an EOF or reset",
                        e.message.orEmpty().contains("SOCKS error 1"))
                }
            }
            assertEquals("invalid CONNECT must not touch upstream", 0, upstreamAttempts.get())
            // The same listener must still accept another authenticated client.
            Socket("127.0.0.1", proxy.boundPort).use { client ->
                client.soTimeout = 3000
                Socks5.authenticateClient(
                    DataInputStream(client.getInputStream()), client.getOutputStream(), "test-pass")
            }
        } finally {
            proxy.stop()
        }
    }
}

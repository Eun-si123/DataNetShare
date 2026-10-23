package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream

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
}

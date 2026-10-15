package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class Socks5ServerTest {
    @Test fun authenticationIsRequiredAndBadCredentialsFail() = ProxyFixture().use { fixture ->
        Socket("127.0.0.1", fixture.proxy.boundPort).use { client ->
            client.soTimeout = 3000; client.getOutputStream().write(byteArrayOf(5, 1, 0))
            val input = DataInputStream(client.getInputStream())
            assertEquals(5, input.readUnsignedByte()); assertEquals(255, input.readUnsignedByte())
            assertEquals(-1, input.read())
        }
        Socket("127.0.0.1", fixture.proxy.boundPort).use { client ->
            client.soTimeout = 3000
            try { Socks5.authenticateClient(DataInputStream(client.getInputStream()), client.getOutputStream(), "wrong"); fail("accepted wrong password") }
            catch (_: java.io.IOException) { }
        }
    }
    @Test fun tcpHalfClosePreservesFullResponseAndLargeUpload() = ProxyFixture().use { fixture ->
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { remote ->
            val payload = ByteArray(1_048_613) { (it * 31).toByte() }
            val hash = MessageDigest.getInstance("SHA-256").digest(payload)
            val result = CompletableFuture<ByteArray>()
            Thread({
                try { remote.accept().use { socket ->
                    socket.soTimeout = 5000
                    val received = socket.getInputStream().readBytes()
                    socket.getOutputStream().write(MessageDigest.getInstance("SHA-256").digest(received))
                    result.complete(received)
                } } catch (e: Exception) { result.completeExceptionally(e) }
            }, "test-half-close").apply { isDaemon = true; start() }
            fixture.client().use { client ->
                Socks5.request(DataInputStream(client.getInputStream()), client.getOutputStream(), 1, Socks5.Address("127.0.0.1", remote.localPort))
                client.getOutputStream().write(payload); client.shutdownOutput()
                assertArrayEquals(hash, client.getInputStream().readBytes())
            }
            assertArrayEquals(payload, result.get(5, TimeUnit.SECONDS))
        }
    }
    @Test fun udpSmallThenLargePacketsAndConcurrentAssociationsStayIsolated() = ProxyFixture().use { fixture ->
        fixture.client().use { first -> fixture.client().use { second ->
            val a = fixture.associate(first); val b = fixture.associate(second)
            assertNotEquals(a.port, b.port)
            DatagramSocket().use { udpA -> DatagramSocket().use { udpB ->
                udpA.soTimeout = 3000; udpB.soTimeout = 3000
                fun roundTrip(socket: DatagramSocket, relay: Socks5.Address, payload: ByteArray) {
                    val frame = Socks5.encodeDatagram(Socks5.Address("127.0.0.1", fixture.udpPort), payload)
                    socket.send(DatagramPacket(frame, frame.size, InetSocketAddress(relay.host, relay.port)))
                    val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                    assertArrayEquals(payload, Socks5.decodeDatagram(packet.data.copyOf(packet.length))!!.payload)
                }
                roundTrip(udpA, a, byteArrayOf(1)); roundTrip(udpB, b, byteArrayOf(2))
                roundTrip(udpA, a, ByteArray(60000) { 31 }); roundTrip(udpB, b, ByteArray(4096) { 42 })
                roundTrip(udpA, a, byteArrayOf(9))
            } }
        } }
    }
    @Test fun stoppingClosesIdleHandshakeAndEstablishedConnections() = ProxyFixture().use { fixture ->
        val partial = Socket("127.0.0.1", fixture.proxy.boundPort).apply { soTimeout = 3000; getOutputStream().write(5) }
        val authenticated = fixture.client()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (fixture.proxy.clientCount < 2 && System.nanoTime() < deadline) Thread.yield()
        fixture.proxy.stop()
        assertEquals(-1, partial.getInputStream().read()); assertEquals(-1, authenticated.getInputStream().read())
        partial.close(); authenticated.close()
    }
    @Test fun unavailableUpstreamReturnsFailureWithoutCrashingListener() = ProxyFixture().use { fixture ->
        val unusedPort = ServerSocket(0).use { it.localPort }
        fixture.client().use { client ->
            try {
                Socks5.request(DataInputStream(client.getInputStream()), client.getOutputStream(), 1, Socks5.Address("127.0.0.1", unusedPort))
                fail("unexpected upstream connection")
            } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("error 5")) }
        }
        fixture.client().close()
    }
    @Test fun activeTcpUpstreamIsClosedByStop() = ProxyFixture().use { fixture ->
        ServerSocket(0).use { destination ->
            fixture.client().use { client ->
                val accepted = CompletableFuture<Socket>()
                Thread({ accepted.complete(destination.accept()) }, "test-stop-accept").apply { isDaemon = true; start() }
                Socks5.request(DataInputStream(client.getInputStream()), client.getOutputStream(), 1, Socks5.Address("127.0.0.1", destination.localPort))
                accepted.get(3, TimeUnit.SECONDS).use { remote ->
                    remote.soTimeout = 3000
                    fixture.proxy.stop()
                    assertEquals(-1, remote.getInputStream().read())
                    assertEquals(-1, client.getInputStream().read())
                }
            }
        }
    }
    @Test fun concurrentClientLimitRejectsExcessWithoutBlockingStop() = ProxyFixture().use { fixture ->
        val sockets = (1..64).map { Socket("127.0.0.1", fixture.proxy.boundPort).apply { soTimeout = 3000 } }
        try {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (fixture.proxy.clientCount < 64 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(64, fixture.proxy.clientCount)
            Socket("127.0.0.1", fixture.proxy.boundPort).use { extra ->
                extra.soTimeout = 3000; assertEquals(-1, extra.getInputStream().read())
            }
            fixture.proxy.stop()
            sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
        } finally { sockets.forEach { it.close() } }
    }

}

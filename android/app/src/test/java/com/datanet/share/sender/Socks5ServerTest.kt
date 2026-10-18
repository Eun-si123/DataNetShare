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
        // Failed authentication must not create a tracked peer.
        assertTrue(fixture.proxy.peerSnapshots().isEmpty())
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
            val usage = fixture.proxy.peerSnapshots().single { it.ip == "127.0.0.1" }
            assertEquals("TCP client-to-Internet usage", payload.size.toLong(), usage.rxBytes)
            assertEquals("TCP Internet-to-client usage", hash.size.toLong(), usage.txBytes)
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
    @Test fun unavailableUpstreamReturnsFailureWithoutCrashingListener() = ProxyFixture(failConnect = true).use { fixture ->
        fixture.client().use { client ->
            try {
                Socks5.request(DataInputStream(client.getInputStream()), client.getOutputStream(), 1, Socks5.Address("127.0.0.1", 12345))
                fail("unexpected upstream connection")
            } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("error 5")) }
        }
        fixture.client().close()
    }
    @Test fun missingCellularNetworkReturnsNetworkUnreachableInsteadOfGenericReset() =
        ProxyFixture(noCellular = true).use { fixture ->
            fixture.client().use { client ->
                try {
                    Socks5.request(DataInputStream(client.getInputStream()), client.getOutputStream(), 1,
                        Socks5.Address("127.0.0.1", 443))
                    fail("expected unreachable mobile data route")
                } catch (e: java.io.IOException) {
                    assertTrue(e.message.orEmpty().contains("SOCKS error 3"))
                }
            }
        }

    @Test fun stopHandlesConnectionsClosingConcurrently() {
        repeat(3) {
            ProxyFixture(maxClients = 64).use { fixture ->
                val sockets = (1..32).map {
                    Socket("127.0.0.1", fixture.proxy.boundPort).apply {
                        soTimeout = 3000
                        // Keep authentication incomplete while the sender stops.
                        getOutputStream().write(5)
                    }
                }
                try {
                    val trigger = java.util.concurrent.CountDownLatch(1)
                    val closer = Thread({
                        trigger.await()
                        sockets.forEach { it.close() }
                    }, "test-concurrent-close").apply { isDaemon = true; start() }
                    trigger.countDown()
                    fixture.proxy.stop() // must never throw NoSuchElementException
                    closer.join(3000)
                    assertFalse("concurrent close thread stalled", closer.isAlive)
                } finally {
                    sockets.forEach { it.close() }
                }
            }
        }
        Unit
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
    @Test fun idleTcpRelayIsReclaimedAndNextClientCanConnect() =
        ProxyFixture(maxClients = 1, tcpIdleMillis = 900, socketPollMillis = 200).use { fixture ->
            ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress()).use { destination ->
                // Remote accepts a TCP connection but never sends any payload or FIN,
                // like a browser keep-alive that would previously hold a proxy slot forever.
                Thread({
                    repeat(2) {
                        try {
                            destination.accept().use { socket ->
                                socket.soTimeout = 5000
                                try { while (socket.getInputStream().read() != -1) {} }
                                catch (_: java.io.IOException) {}
                            }
                        } catch (_: java.io.IOException) { return@Thread }
                    }
                }, "test-idle-proxy").apply { isDaemon = true; start() }
                fixture.client().use { first ->
                    Socks5.request(DataInputStream(first.getInputStream()), first.getOutputStream(), 1,
                        Socks5.Address("127.0.0.1", destination.localPort))
                    assertEquals(1, fixture.proxy.clientCount)
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (fixture.proxy.clientCount > 0 && System.nanoTime() < deadline) {
                        Thread.sleep(25)
                    }
                    assertEquals("idle TCP client was never reclaimed", 0, fixture.proxy.clientCount)
                    assertEquals("both read directions must report only one cleanup", 1,
                        fixture.idleClosures.get())
                    fixture.client().use { second ->
                        Socks5.request(DataInputStream(second.getInputStream()), second.getOutputStream(), 1,
                            Socks5.Address("127.0.0.1", destination.localPort))
                    }
                }
            }
            Unit // JUnit4 requires the generated @Test method to return void.
        }

    @Test fun concurrentClientLimitRejectsExcessWithoutBlockingStop() = ProxyFixture(maxClients = 64).use { fixture ->
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

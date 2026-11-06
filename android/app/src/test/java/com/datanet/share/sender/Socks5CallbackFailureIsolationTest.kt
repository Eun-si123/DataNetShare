package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Socks5CallbackFailureIsolationTest {
    @Test fun throwingIdleReporterCannotEscapeRelayOrStrandClientSlot() {
        val uncaught = LinkedBlockingQueue<Throwable>()
        val idleCalls = AtomicInteger()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { remote ->
            val upstream = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket =
                    Socket().apply { connect(InetSocketAddress(host, port), 1_000) }
                override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
                override fun datagram(): DatagramSocket = DatagramSocket()
            }
            val proxy = Socks5Server("127.0.0.1", 0, "synthetic-password", upstream,
                onIdleClose = {
                    idleCalls.incrementAndGet()
                    throw IllegalStateException("private UI callback details")
                },
                maxClients = 1, tcpIdleMillis = 700, socketPollMillis = 100,
                workerLauncher = { thread ->
                    thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                        uncaught.add(error)
                    }
                    thread.start()
                }
            )
            val acceptor = Thread({
                try {
                    remote.accept().use { held ->
                        held.soTimeout = 4_000
                        try { while (held.getInputStream().read() != -1) {} }
                        catch (_: IOException) { }
                    }
                } catch (_: IOException) { }
            }, "test-throwing-idle-close-upstream").apply { isDaemon = true; start() }
            proxy.start()
            try {
                Socket("127.0.0.1", proxy.boundPort).use { first ->
                    first.soTimeout = 4_000
                    val input = DataInputStream(first.getInputStream())
                    Socks5.authenticateClient(input, first.getOutputStream(), "synthetic-password")
                    Socks5.request(input, first.getOutputStream(), 1,
                        Socks5.Address("127.0.0.1", remote.localPort))
                    assertEquals("idle timeout must close both relay directions", -1, input.read())
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                while (proxy.clientCount != 0 && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals("idle client must be reclaimed", 0, proxy.clientCount)
                assertEquals("one telemetry notification per idle close", 1, idleCalls.get())
                assertTrue("optional UI callback exception escaped relay worker", uncaught.isEmpty())
                // A reclaimed session must admit a fresh authenticated client.
                val nextDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (true) {
                    try {
                        Socket("127.0.0.1", proxy.boundPort).use { next ->
                            next.soTimeout = 1_000
                            Socks5.authenticateClient(DataInputStream(next.getInputStream()),
                                next.getOutputStream(), "synthetic-password")
                        }
                        break
                    } catch (failure: IOException) {
                        if (System.nanoTime() >= nextDeadline) throw AssertionError(
                            "idle cleanup stranded the only client permit", failure)
                        Thread.sleep(10)
                    }
                }
            } finally {
                proxy.stop()
                remote.close()
                acceptor.join(2_000)
                assertFalse("upstream acceptor never terminated", acceptor.isAlive)
            }
        }
    }

    @Test fun throwingRuntimeClientLimitReporterCannotDisableAcceptLoop() =
        verifyCallbackFailure(IllegalStateException("simulated runtime metrics failure"))

    @Test fun throwingIoClientLimitReporterCannotDisableAcceptLoop() =
        verifyCallbackFailure(IOException("simulated IO metrics failure"))

    @Test fun throwingStatsAndPeerReportersDoNotDisablePeriodicReportingOrClients() {
        // Both callbacks fail repeatedly. Each must remain independently
        // observable, and neither may terminate the periodic worker.
        val statsCalls = CountDownLatch(2)
        val peerCalls = CountDownLatch(2)
        val upstream = object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket = throw IOException("unused")
            override fun resolve(host: String): Array<InetAddress> = throw IOException("unused")
            override fun datagram(): DatagramSocket = throw IOException("unused")
        }
        val proxy = Socks5Server(
            "127.0.0.1", 0, "test-pass", upstream,
            onStats = { _, _, _ ->
                statsCalls.countDown()
                throw IllegalStateException("simulated stats UI failure")
            },
            onPeers = {
                peerCalls.countDown()
                throw IOException("simulated peer UI failure")
            }
        )
        proxy.start()
        try {
            assertTrue("stats must still run after the first callback failure",
                statsCalls.await(6, TimeUnit.SECONDS))
            assertTrue("peer reporting must continue despite both callback failures",
                peerCalls.await(6, TimeUnit.SECONDS))
            Socket("127.0.0.1", proxy.boundPort).use { accepted ->
                accepted.soTimeout = 3000
                Socks5.authenticateClient(
                    DataInputStream(accepted.getInputStream()),
                    accepted.getOutputStream(),
                    "test-pass"
                )
            }
        } finally {
            proxy.stop()
        }
    }

    @Test fun throwingUpstreamFailureReporterStillReturnsSocksError() {
        // An optional UI callback must not replace the RFC 1928 failure reply
        // with an unexpected EOF or break later authenticated requests.
        val failures = AtomicInteger()
        val upstream = object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket =
                throw IOException("simulated upstream connection failure")
            override fun resolve(host: String): Array<InetAddress> = throw IOException("unused")
            override fun datagram(): DatagramSocket = throw IOException("unused")
        }
        val proxy = Socks5Server(
            "127.0.0.1", 0, "test-pass", upstream,
            onUpstreamFailure = {
                failures.incrementAndGet()
                throw IllegalStateException("simulated upstream UI failure")
            }
        )
        proxy.start()
        try {
            repeat(2) {
                Socket("127.0.0.1", proxy.boundPort).use { client ->
                    client.soTimeout = 3000
                    val input = DataInputStream(client.getInputStream())
                    val output = client.getOutputStream()
                    Socks5.authenticateClient(input, output, "test-pass")
                    try {
                        Socks5.request(input, output, 1, Socks5.Address("example.test", 443))
                        fail("failed upstream must not report success")
                    } catch (e: IOException) {
                        assertTrue("client must receive SOCKS error 5, not EOF",
                            e.message.orEmpty().contains("SOCKS error 5"))
                    }
                }
            }
            assertEquals("each failed request must report once", 2, failures.get())
        } finally {
            proxy.stop()
        }
    }

    private fun verifyCallbackFailure(failure: Exception) {
        // Reporting a rejected client is optional telemetry. It must not
        // strand the rejected socket or disable the authenticated listener.
        val reports = AtomicInteger()
        val upstream = object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket = throw IOException("unused")
            override fun resolve(host: String): Array<InetAddress> = throw IOException("unused")
            override fun datagram(): DatagramSocket = throw IOException("unused")
        }
        val proxy = Socks5Server(
            "127.0.0.1", 0, "test-pass", upstream,
            onClientLimit = {
                reports.incrementAndGet()
                throw failure
            },
            maxClients = 1
        )
        proxy.start()
        try {
            Socket("127.0.0.1", proxy.boundPort).use { held ->
                held.soTimeout = 3000
                val occupiedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (proxy.clientCount != 1 && System.nanoTime() < occupiedDeadline) {
                    Thread.sleep(5)
                }
                assertEquals("first client must occupy the only permit", 1, proxy.clientCount)

                Socket("127.0.0.1", proxy.boundPort).use { rejected ->
                    rejected.soTimeout = 3000
                    val callbackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (reports.get() == 0 && System.nanoTime() < callbackDeadline) {
                        Thread.sleep(5)
                    }
                    assertEquals("limit callback must have been invoked", 1, reports.get())
                    assertEquals("rejected socket must close despite callback exception",
                        -1, rejected.getInputStream().read())
                }

                held.close()
                val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (proxy.clientCount != 0 && System.nanoTime() < releaseDeadline) {
                    Thread.sleep(5)
                }
                assertEquals("first client's permit must be released", 0, proxy.clientCount)

                Socket("127.0.0.1", proxy.boundPort).use { accepted ->
                    accepted.soTimeout = 3000
                    Socks5.authenticateClient(
                        DataInputStream(accepted.getInputStream()),
                        accepted.getOutputStream(),
                        "test-pass"
                    )
                }
                assertEquals("reporter failure must not affect later clients", 1, reports.get())
            }
        } finally {
            proxy.stop()
        }
    }
}

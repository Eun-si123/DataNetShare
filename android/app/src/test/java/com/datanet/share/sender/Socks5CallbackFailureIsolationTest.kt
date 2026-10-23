package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Socks5CallbackFailureIsolationTest {
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

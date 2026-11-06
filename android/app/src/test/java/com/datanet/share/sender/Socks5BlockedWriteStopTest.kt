package com.datanet.share.sender

import com.datanet.share.common.Socks5
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only controlled blocked-write fixture; not an Android/kernel socket backpressure test. */
class Socks5BlockedWriteStopTest {
    private class BlockedRemote : Socket() {
        val writeEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                closed.await()
                return -1
            }
        }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                writeEntered.countDown()
                closed.await()
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeEntered.countDown()
                closed.await()
            }
        }
        override fun close() {
            closed.countDown()
            super.close()
        }
    }

    @Test fun stopClosesBlockedUpstreamWriteAndReleasesClientSlot() {
        val remote = AtomicReference<BlockedRemote>()
        val proxy = Socks5Server("127.0.0.1", 0, "synthetic-session-password",
            object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket =
                    BlockedRemote().also(remote::set)
                override fun resolve(host: String): Array<InetAddress> = emptyArray()
                override fun datagram(): DatagramSocket = error("UDP not used")
            }, maxClients = 1, socketPollMillis = 200)
        proxy.start()
        try {
            Socket("127.0.0.1", proxy.boundPort).use { client ->
                client.soTimeout = 3000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                Socks5.authenticateClient(input, output, "synthetic-session-password")
                Socks5.request(input, output, 1, Socks5.Address("127.0.0.1", 443))
                output.write(byteArrayOf(1, 2, 3, 4))
                val upstream = requireNotNull(remote.get())
                assertTrue("upload did not enter controlled blocked write",
                    upstream.writeEntered.await(3, TimeUnit.SECONDS))
                proxy.stop()
                assertTrue("Stop did not close the blocked upstream socket",
                    upstream.closed.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (proxy.clientCount != 0 && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals("blocked upload retained an authenticated SOCKS client", 0, proxy.clientCount)
                // Count alone cannot prove the semaphore permit was returned.
                // Restart the same proxy and authenticate a fresh control connection.
                proxy.start()
                val reuseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var reused = false
                while (!reused && System.nanoTime() < reuseDeadline) {
                    try {
                        Socket("127.0.0.1", proxy.boundPort).use { next ->
                            next.soTimeout = 500
                            Socks5.authenticateClient(DataInputStream(next.getInputStream()),
                                next.getOutputStream(), "synthetic-session-password")
                            reused = true
                        }
                    } catch (_: IOException) {
                        Thread.sleep(20) // brief worker permit-release race
                    }
                }
                assertTrue("blocked upload stranded the only SOCKS client permit", reused)
            }
        } finally {
            proxy.stop()
        }
    }
}

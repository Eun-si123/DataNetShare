package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PeerUsageLedgerTest {
    @Test fun aggregatesConnectionsByIpAndBothDirections() {
        val usage = PeerUsageLedger()
        val a = usage.open("192.168.49.81")!!
        val b = usage.open("192.168.49.81")!!
        val other = usage.open("192.168.49.82")!!
        a.received(1024); b.received(10)
        a.sent(2048); b.sent(15)
        other.sent(100)
        assertEquals(2, usage.snapshot().first().activeConnections)
        val result = usage.snapshot().first { it.ip == "192.168.49.81" }
        assertEquals(1034L, result.rxBytes)
        assertEquals(2063L, result.txBytes)
        usage.close(a); usage.close(b)
        assertEquals(0, usage.snapshot().first { it.ip == "192.168.49.81" }.activeConnections)
        assertEquals(1, usage.snapshot().first { it.ip == "192.168.49.82" }.activeConnections)
    }

    @Test fun capsTrackedPeersAndDoesNotGoNegative() {
        val ledger = PeerUsageLedger()
        repeat(32) { assertNotNull(ledger.open("10.0.0.$it")) }
        assertNull(ledger.open("10.0.1.1"))
        assertEquals(32, ledger.snapshot().size)
        val entry = ledger.open("10.0.0.0")!!
        ledger.close(entry); ledger.close(entry); ledger.close(entry)
        assertEquals(0, ledger.snapshot().first { it.ip == "10.0.0.0" }.activeConnections)
        ledger.clear()
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test fun concurrentTrafficCountersRemainAccurate() {
        val usage = PeerUsageLedger()
        val ref = usage.open("192.168.49.81")!!
        val threads = (0 until 6).map {
            Thread { repeat(2000) { ref.received(17); ref.sent(13) } }.apply { start() }
        }
        threads.forEach { it.join() }
        val stats = usage.snapshot().single()
        assertEquals(6L * 2000 * 17, stats.rxBytes)
        assertEquals(6L * 2000 * 13, stats.txBytes)
    }

    @Test fun concurrentUniquePeersNeverBypassTrackingLimit() {
        val usage = PeerUsageLedger()
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        try {
            val attempts = (0 until 96).map { index ->
                pool.submit<Boolean> {
                    start.await()
                    usage.open("10.0.${index / 256}.${index % 256}") != null
                }
            }
            start.countDown()
            assertEquals(32, attempts.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(32, usage.snapshot().size)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}

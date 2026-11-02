package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PeerUsageLedgerTest {
    @Test fun snapshotDebugStringCannotRevealPeerIp() {
        val peerIp = "192.0.2.42" // RFC 5737 documentation-only address
        val ledger = PeerUsageLedger()
        val lease = ledger.open(peerIp)!!
        lease.received(17)
        val snapshot = ledger.snapshot().single()

        // Fields remain available to the local UI; implicit logs must be redacted.
        assertEquals(peerIp, snapshot.ip)
        assertEquals(17L, snapshot.rxBytes)
        assertFalse(snapshot.toString().contains(peerIp))
        assertTrue(snapshot.toString().contains("redacted"))
        ledger.close(lease)
    }

    @Test fun aggregatesConnectionsByIpAndBothDirections() {
        val usage = PeerUsageLedger()
        val a = usage.open("192.168.49.81")!!
        val b = usage.open("192.168.49.81")!!
        assertNotSame("each connection needs an independent close lease", a, b)
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
        val original = (0 until 32).map { ledger.open("10.0.0.$it")!! }
        assertNull(ledger.open("10.0.1.1"))
        assertEquals(32, ledger.snapshot().size)
        val entry = ledger.open("10.0.0.0")!!
        ledger.close(entry); ledger.close(entry); ledger.close(entry)
        assertEquals(1, ledger.snapshot().first { it.ip == "10.0.0.0" }.activeConnections)
        ledger.close(original.first())
        assertEquals(0, ledger.snapshot().first { it.ip == "10.0.0.0" }.activeConnections)
        ledger.clear()
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test fun newPeerReusesOldestInactiveSlotWithoutLosingActivePeers() {
        val ledger = PeerUsageLedger()
        val oldest = ledger.open("10.0.0.0")!!
        ledger.close(oldest)
        val active = (1..31).map { ledger.open("10.0.0.$it")!! }
        assertEquals(32, ledger.snapshot().size)
        val newcomer = ledger.open("10.0.1.1")
        assertNotNull("inactive history must not block new peer accounting", newcomer)
        val peers = ledger.snapshot()
        assertEquals(32, peers.size)
        assertFalse(peers.any { it.ip == "10.0.0.0" })
        assertEquals(32, peers.count { it.activeConnections == 1 })
        active.forEach(ledger::close)
        ledger.close(newcomer)
    }

    @Test fun recentlyReopenedIdlePeerIsNotEvictedBeforeOlderIdlePeer() {
        val ledger = PeerUsageLedger()
        val recent = ledger.open("10.0.0.1")!!
        ledger.close(recent)
        val old = ledger.open("10.0.0.2")!!
        ledger.close(old)
        repeat(30) { ledger.open("10.0.1.$it") }
        ledger.close(ledger.open("10.0.0.1"))
        assertNotNull(ledger.open("10.0.2.1"))
        assertTrue(ledger.snapshot().any { it.ip == "10.0.0.1" })
        assertFalse(ledger.snapshot().any { it.ip == "10.0.0.2" })
    }

    @Test fun staleWorkersCannotAlterReopenedSessionForSamePeer() {
        val ledger = PeerUsageLedger()
        val stale = ledger.open("192.0.2.10")!!
        stale.received(200)
        stale.sent(300)

        // Stop/restart can clear accounting while old socket workers finish.
        ledger.clear()
        val current = ledger.open("192.0.2.10")!!
        val second = ledger.open("192.0.2.10")!!
        stale.received(500)
        stale.sent(700)
        ledger.close(stale)

        val fresh = ledger.snapshot().single()
        assertEquals(2, fresh.activeConnections)
        assertEquals(0L, fresh.rxBytes)
        assertEquals(0L, fresh.txBytes)

        current.received(11)
        second.sent(13)
        ledger.close(current)
        val afterClose = ledger.snapshot().single()
        assertEquals(1, afterClose.activeConnections)
        assertEquals(11L, afterClose.rxBytes)
        assertEquals(13L, afterClose.txBytes)
        ledger.close(second)
    }

    @Test fun lateUdpAccountingAfterLeaseCloseCannotPolluteReconnectedPeer() {
        val ledger = PeerUsageLedger()
        val oldConnection = ledger.open("198.51.100.9")!!
        oldConnection.received(100)
        ledger.close(oldConnection)

        // An upstream UDP worker may finish after the owning SOCKS client
        // has closed, while another connection from the same IP is active.
        val newConnection = ledger.open("198.51.100.9")!!
        oldConnection.received(400)
        oldConnection.sent(600)
        val snapshot = ledger.snapshot().single()
        assertEquals(1, snapshot.activeConnections)
        assertEquals(100L, snapshot.rxBytes)
        assertEquals(0L, snapshot.txBytes)

        newConnection.received(25)
        newConnection.sent(50)
        assertEquals(125L, ledger.snapshot().single().rxBytes)
        assertEquals(50L, ledger.snapshot().single().txBytes)
        ledger.close(newConnection)
    }

    @Test fun duplicateCloseCannotReleaseAnotherConnectionForSameIp() {
        val ledger = PeerUsageLedger()
        val first = ledger.open("198.51.100.1")!!
        val second = ledger.open("198.51.100.1")!!
        assertEquals(2, ledger.snapshot().single().activeConnections)

        // A second cleanup of the same connection must not release the
        // other connection's accounting slot or make it eligible for eviction.
        ledger.close(first)
        ledger.close(first)
        assertEquals(1, ledger.snapshot().single().activeConnections)
        repeat(31) { assertNotNull(ledger.open("198.51.101.$it")) }
        assertNull("an active peer must not be evicted", ledger.open("198.51.102.1"))
        assertTrue(ledger.snapshot().any { it.ip == "198.51.100.1" })
        ledger.close(second)
    }

    @Test fun concurrentDuplicateCloseReleasesOnlyItsOwnLease() {
        val ledger = PeerUsageLedger()
        val first = ledger.open("198.51.100.2")!!
        val second = ledger.open("198.51.100.2")!!
        val workers = (0 until 2).map {
            Thread { repeat(100) { ledger.close(first) } }.apply { start() }
        }
        workers.forEach { it.join() }
        assertEquals(1, ledger.snapshot().single().activeConnections)
        ledger.close(second)
        assertEquals(0, ledger.snapshot().single().activeConnections)
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

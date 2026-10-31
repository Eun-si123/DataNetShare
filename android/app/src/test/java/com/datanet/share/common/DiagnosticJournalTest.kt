package com.datanet.share.common

import com.datanet.share.receiver.TcpCapacityPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticJournalTest {
    @Test fun emptyJournalMakesNoUnverifiedSuccessClaim() {
        val journal = DiagnosticJournal()
        assertTrue(journal.snapshot().contains("does not confirm Internet"))
    }
    @Test fun repeatedConnectionErrorsAreVisibleAndCounted() {
        val journal = DiagnosticJournal()
        assertTrue(journal.record(NetworkEvent.TCP_UPSTREAM).contains("×1"))
        assertTrue(journal.record(NetworkEvent.TCP_UPSTREAM).contains("×2"))
        assertTrue(journal.record(NetworkEvent.UDP_SETUP).startsWith("Receiver UDP"))
        assertTrue(journal.snapshot().contains("TCP upstream"))
        assertFalse(journal.snapshot().contains("192.168"))
    }
    @Test fun entriesAreBoundedAndResetByNewSession() {
        val journal = DiagnosticJournal(2)
        journal.record(NetworkEvent.TCP_UPSTREAM)
        journal.record(NetworkEvent.UDP_SETUP)
        journal.record(NetworkEvent.TUN_CONGESTION)
        assertFalse(journal.snapshot().contains("TCP upstream"))
        assertTrue(journal.snapshot().contains("temporarily congested"))
        journal.clear()
        assertTrue(journal.snapshot().contains("No internal"))
    }
    @Test fun udpCapacityDiagnosticCoversSharedBudgetNotOnlyFortyUdpFlows() {
        // UDP can be rejected with only eight UDP flows when 112 TCP flows
        // consume the remainder of the 120-slot shared sender budget.
        val tcpAtCap = TcpCapacityPolicy.MAX_TCP
        val udpAtSharedCap = TcpCapacityPolicy.TOTAL_BUDGET - tcpAtCap
        assertTrue(udpAtSharedCap < TcpCapacityPolicy.MAX_UDP)
        assertFalse(TcpCapacityPolicy.allowUdp(tcpAtCap, udpAtSharedCap))
        assertFalse(TcpCapacityPolicy.allowUdp(0, TcpCapacityPolicy.MAX_UDP))
        val summary = DiagnosticJournal().record(NetworkEvent.UDP_FLOW_LIMIT)
        assertTrue(summary.contains("shared TCP/UDP budget"))
        assertFalse(summary.contains("40 concurrent flows"))
        assertTrue(summary.endsWith("×1"))
    }

    @Test fun sessionResetCannotBeOverwrittenByDelayedFailurePublication() {
        val journal = DiagnosticJournal()
        val published = Collections.synchronizedList(mutableListOf<String>())
        val firstPublishEntered = CountDownLatch(1)
        val releaseFirstPublish = CountDownLatch(1)
        val resetStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val note = workers.submit {
                journal.recordAndPublish(NetworkEvent.TCP_UPSTREAM) { snapshot ->
                    firstPublishEntered.countDown()
                    check(releaseFirstPublish.await(5, TimeUnit.SECONDS))
                    published.add(snapshot)
                }
            }
            assertTrue(firstPublishEntered.await(5, TimeUnit.SECONDS))
            val reset = workers.submit {
                resetStarted.countDown()
                journal.clearAndPublish { published.add(it) }
            }
            assertTrue(resetStarted.await(5, TimeUnit.SECONDS))
            releaseFirstPublish.countDown()
            note.get(5, TimeUnit.SECONDS)
            reset.get(5, TimeUnit.SECONDS)
            assertEquals(2, published.size)
            assertTrue(published[0].contains("TCP upstream"))
            assertTrue(published[1].contains("No internal network failures"))
            assertEquals(published[1], journal.snapshot())
        } finally {
            releaseFirstPublish.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun concurrentEventsDoNotLoseCounts() {
        val journal = DiagnosticJournal()
        val latch = CountDownLatch(4)
        repeat(4) {
            Thread({
                repeat(100) { journal.record(NetworkEvent.UDP_RELAY) }
                latch.countDown()
            }).start()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(journal.snapshot().contains("×400"))
    }
}

package com.datanet.share.common

import com.datanet.share.receiver.TcpCapacityPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
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

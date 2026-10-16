package com.datanet.share.common

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
        assertTrue(journal.snapshot().contains("TUN"))
        journal.clear()
        assertTrue(journal.snapshot().contains("No internal"))
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

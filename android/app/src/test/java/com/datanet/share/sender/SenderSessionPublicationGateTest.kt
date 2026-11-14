package com.datanet.share.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SenderSessionPublicationGateTest {
    @Test fun staleSessionCannotPublishAfterTeardown() {
        val gate = SenderSessionPublicationGate()
        val generation = AtomicInteger(1)
        val output = mutableListOf<String>()
        gate.publishIfCurrent(1, generation::get) { output.add("old") }
        generation.incrementAndGet()
        gate.clearAfterTeardown { output.clear() }
        gate.publishIfCurrent(1, generation::get) { output.add("stale") }
        gate.publishIfCurrent(2, generation::get) { output.add("new") }
        assertEquals(listOf("new"), output)
    }

    @Test fun teardownWaitsForInFlightPublication() {
        val gate = SenderSessionPublicationGate()
        val generation = AtomicInteger(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val publisher = Thread {
            gate.publishIfCurrent(1, generation::get) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }
        publisher.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        generation.incrementAndGet()
        val teardown = Thread { gate.clearAfterTeardown { cleared.countDown() } }
        teardown.start()
        try {
            assertFalse(cleared.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            publisher.join(3000)
            teardown.join(3000)
        }
        assertTrue(cleared.await(3, TimeUnit.SECONDS))
    }
}

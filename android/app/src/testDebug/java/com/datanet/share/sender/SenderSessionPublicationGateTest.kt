package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SenderSessionPublicationGateTest {
    @Test fun inFlightOldSessionPostCannotOverwriteFailureClear() {
        val gate = SenderSessionPublicationGate()
        val generation = AtomicInteger(7)
        val published = ConcurrentLinkedQueue<String>()
        val insideOldPost = CountDownLatch(1)
        val allowOldPost = CountDownLatch(1)
        val clearStarted = CountDownLatch(1)
        val clearFinished = CountDownLatch(1)
        val backgroundFailure = AtomicReference<Throwable>()
        val old = Thread {
            try {
                gate.publishIfCurrent(7, { generation.get() }) {
                    insideOldPost.countDown()
                    assertTrue(allowOldPost.await(3, TimeUnit.SECONDS))
                    published.add("old peer IP and counters")
                }
            } catch (error: Throwable) { backgroundFailure.set(error) }
        }
        old.start()
        try {
            assertTrue("old callback did not reach publication", insideOldPost.await(3, TimeUnit.SECONDS))
            generation.set(8) // SenderService.cleanup() invalidates its old callbacks.
            val clear = Thread {
                try {
                    clearStarted.countDown()
                    gate.clearAfterTeardown {
                        published.clear()
                        clearFinished.countDown()
                    }
                } catch (error: Throwable) { backgroundFailure.set(error) }
            }
            clear.start()
            assertTrue(clearStarted.await(3, TimeUnit.SECONDS))
            assertFalse("clear raced ahead of the in-flight post",
                clearFinished.await(100, TimeUnit.MILLISECONDS))
            allowOldPost.countDown()
            assertTrue("terminal clear did not complete", clearFinished.await(3, TimeUnit.SECONDS))
            old.join(3000)
            clear.join(3000)
            assertFalse(old.isAlive)
            assertFalse(clear.isAlive)
            assertNull("background thread failed", backgroundFailure.get())
            gate.publishIfCurrent(7, { generation.get() }) { published.add("late stale post") }
            assertTrue("old-session data was restored", published.isEmpty())
            gate.publishIfCurrent(8, { generation.get() }) { published.add("new session") }
            assertEquals(listOf("new session"), published.toList())
        } finally {
            allowOldPost.countDown()
            old.join(3000)
        }
    }
}

package com.datanet.share.common

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionStoreConcurrencyTest {
    // Keep LiveData.postValue deterministic without an Android main Looper.
    @Before fun useSynchronousLiveData() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        SessionStore.publish(SessionStore.State())
    }

    @After fun restoreExecutorAndState() {
        SessionStore.publish(SessionStore.State())
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test fun stoppingCurrentModeClearsState() {
        SessionStore.publish(SessionStore.State(SessionStore.Mode.RECEIVER, "active"))
        SessionStore.stopped(SessionStore.Mode.RECEIVER)
        assertEquals(SessionStore.Mode.NONE, SessionStore.current.mode)
        assertFalse(SessionStore.current.busy)
    }

    @Test fun lateReceiverStopCannotEraseNewSenderState() {
        // Model a stale stop callback racing with a different mode's claim.
        SessionStore.publish(SessionStore.State(SessionStore.Mode.RECEIVER, "stopped"))
        val entered = CountDownLatch(1)
        val stop = Thread {
            entered.countDown()
            SessionStore.stopped(SessionStore.Mode.RECEIVER)
        }
        synchronized(SessionStore) {
            stop.start()
            assertTrue("stop worker did not start", entered.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (stop.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(2)
            }
            assertEquals("stop worker did not reach the state lock", Thread.State.BLOCKED, stop.state)
            // A different session becomes active while the stale callback is waiting.
            SessionStore.publish(SessionStore.State(SessionStore.Mode.SENDER, "starting"))
        }
        stop.join(5_000)
        assertFalse("stop worker did not finish", stop.isAlive)
        assertEquals(SessionStore.Mode.SENDER, SessionStore.current.mode)
        assertEquals("starting", SessionStore.current.status)
    }
}

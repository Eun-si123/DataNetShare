package com.datanet.share.receiver

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FlowAdmissionTest {
    @Test fun stoppedBridgeDoesNotCreateOrRegisterAFlow() {
        val result = admitFlow<Any>({ false },
            { error("must not create after Stop") },
            { error("must not register after Stop") },
            { error("nothing was created to close") })
        assertNull(result)
    }

    @Test fun runningBridgeRegistersBeforeStartingWorkers() {
        val flow = TestFlow()
        val active = ConcurrentHashMap<Int, TestFlow>()
        assertSame(flow, admitFlow({ true }, { flow }, { active[1] = it }, { it.close() }))
        assertSame(flow, active[1])
        assertFalse(flow.closed.get())
    }

    @Test fun stopWhileCreatingClosesFlowRegisteredAfterMapWasCleared() {
        stopDuringAdmission(pauseDuringCreation = true)
    }

    @Test fun stopAfterRegistrationClosesFlowBeforeWorkersCanStart() {
        stopDuringAdmission(pauseDuringCreation = false)
    }

    @Test fun failedRegistrationClosesTheCreatedFlow() {
        val created = TestFlow()
        val failure = IllegalStateException("register failed")
        var closes = 0
        try {
            admitFlow({ true }, { created }, { throw failure }, {
                closes++
                it.close()
            })
            fail("registration failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals(1, closes)
        assertTrue(created.closed.get())
    }

    @Test fun failedPostRegistrationCheckClosesThePublishedFlow() {
        val created = TestFlow()
        val active = ConcurrentHashMap<Int, TestFlow>()
        val failure = IllegalStateException("running check failed")
        var checks = 0
        try {
            admitFlow(
                isRunning = { if (++checks == 2) throw failure else true },
                create = { created },
                register = { active[1] = it },
                close = { it.close(); active.remove(1, it) }
            )
            fail("status check failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertTrue(created.closed.get())
        assertTrue(active.isEmpty())
    }

    @Test fun cleanupFailureDoesNotHideRegistrationFailure() {
        val registrationFailure = IllegalStateException("register failed")
        val cleanupFailure = IllegalArgumentException("cleanup failed")
        try {
            admitFlow({ true }, { TestFlow() }, { throw registrationFailure }, { throw cleanupFailure })
            fail("registration failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(registrationFailure, caught)
            assertEquals(1, caught.suppressed.size)
            assertSame(cleanupFailure, caught.suppressed[0])
        }
    }

    @Test fun postRegistrationFailureKeepsOriginalWhenCleanupAlsoFails() {
        val stateFailure = IllegalStateException("post-registration state check failed")
        val cleanupFailure = IllegalArgumentException("cleanup failed")
        var checks = 0
        var cleanupCalls = 0
        try {
            admitFlow(
                isRunning = { if (++checks == 2) throw stateFailure else true },
                create = { TestFlow() },
                register = { },
                close = { cleanupCalls++; throw cleanupFailure }
            )
            fail("Post-registration failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(stateFailure, caught)
            assertEquals(1, caught.suppressed.size)
            assertSame(cleanupFailure, caught.suppressed[0])
        }
        assertEquals(2, checks)
        assertEquals(1, cleanupCalls)
    }

    @Test fun registrationErrorStillClosesFlowBeforePropagating() {
        val flow = TestFlow()
        val failure = AssertionError("registration error")
        try {
            admitFlow({ true }, { flow }, { throw failure }, { it.close() })
            fail("Registration Error must propagate")
        } catch (caught: AssertionError) {
            assertSame(failure, caught)
        }
        assertTrue(flow.closed.get())
    }

    @Test fun admittedTcpWorkerLaunchFailureReclaimsRegisteredFlow() {
        val flow = TestFlow()
        val active = ConcurrentHashMap<Int, TestFlow>()
        val admitted = admitFlow({ true }, { flow }, { active[1] = it },
            { it.close(); active.remove(1, it) })!!
        var failures = 0
        val launched = launchAdmittedFlow(admitted,
            launch = { throw OutOfMemoryError("synthetic thread limit") },
            close = { it.close(); active.remove(1, it) },
            onThreadExhausted = {
                assertTrue("cleanup must precede the failure callback", flow.closed.get())
                assertTrue("the published flow must be removed", active.isEmpty())
                failures++
            })
        assertFalse(launched)
        assertTrue(flow.closed.get())
        assertTrue(active.isEmpty())
        assertEquals(1, failures)
    }

    @Test fun admittedUdpWorkerSecurityFailureReclaimsAndRethrows() {
        val flow = TestFlow()
        val active = ConcurrentHashMap<Int, TestFlow>()
        val admitted = admitFlow({ true }, { flow }, { active[1] = it },
            { it.close(); active.remove(1, it) })!!
        val failure = SecurityException("synthetic launch failure")
        try {
            launchAdmittedFlow(admitted,
                launch = { throw failure },
                close = { it.close(); active.remove(1, it) },
                onThreadExhausted = { fail("not native thread exhaustion") })
            fail("security failure must propagate")
        } catch (caught: SecurityException) { assertSame(failure, caught) }
        assertTrue(flow.closed.get())
        assertTrue(active.isEmpty())
    }

    @Test fun admittedWorkerLaunchSuccessKeepsRegisteredFlow() {
        val flow = TestFlow()
        var launches = 0
        assertTrue(launchAdmittedFlow(flow, { launches++ },
            { fail("successful worker must not be closed") },
            { fail("successful worker must not fail") }))
        assertEquals(1, launches)
        assertFalse(flow.closed.get())
    }

    @Test fun admittedWorkerCleanupFailurePreservesOriginalLaunchFailure() {
        val failure = SecurityException("synthetic launch failure")
        val cleanup = IllegalStateException("synthetic cleanup failure")
        try {
            launchAdmittedFlow(TestFlow(), { throw failure }, { throw cleanup },
                { fail("not native thread exhaustion") })
            fail("failure must propagate")
        } catch (caught: SecurityException) {
            assertSame(failure, caught)
            assertEquals(1, caught.suppressed.size)
            assertSame(cleanup, caught.suppressed[0])
        }
    }

    @Test fun nativeThreadExhaustionWithCleanupFailureKeepsOriginalError() {
        val failure = OutOfMemoryError("synthetic native thread limit")
        val cleanup = IllegalStateException("synthetic cleanup failure")
        try {
            launchAdmittedFlow(TestFlow(), { throw failure }, { throw cleanup },
                { fail("failed cleanup must not report a clean recovery") })
            fail("original error must propagate if cleanup also fails")
        } catch (caught: OutOfMemoryError) {
            assertSame(failure, caught)
            assertEquals(1, caught.suppressed.size)
            assertSame(cleanup, caught.suppressed[0])
        }
    }

    private fun stopDuringAdmission(pauseDuringCreation: Boolean) {
        val running = AtomicBoolean(true)
        val reached = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val active = ConcurrentHashMap<Int, TestFlow>()
        val created = TestFlow()
        val admitted = AtomicReference<TestFlow?>()
        val failure = AtomicReference<Throwable?>()
        fun pause() {
            reached.countDown()
            check(proceed.await(5, TimeUnit.SECONDS)) { "Stop did not release admission" }
        }
        val worker = Thread({
            try {
                admitted.set(admitFlow(
                    isRunning = { running.get() },
                    create = { if (pauseDuringCreation) pause(); created },
                    register = { active[1] = it; if (!pauseDuringCreation) pause() },
                    close = { it.close(); active.remove(1, it) }))
            } catch (error: Throwable) { failure.set(error) }
        }, "test-flow-admission").apply { isDaemon = true; start() }
        try {
            assertTrue("admission did not reach the controlled Stop boundary",
                reached.await(5, TimeUnit.SECONDS))
            // Same ordering as Tun2Socks.stop(): revoke admission, close known
            // flows, then clear the maps before the packet reader continues.
            running.set(false)
            active.values.forEach { it.close() }
            active.clear()
        } finally {
            proceed.countDown()
            worker.join(5000)
        }
        assertFalse("admission worker did not finish", worker.isAlive)
        failure.get()?.let { throw AssertionError("admission worker failed", it) }
        assertNull("a stopped bridge must not start socket workers", admitted.get())
        assertTrue("created flow must be closed even if Stop missed its registration", created.closed.get())
        assertTrue("Stop must leave no active flow", active.isEmpty())
    }

    private class TestFlow {
        val closed = AtomicBoolean()
        fun close() { closed.set(true) }
    }
}

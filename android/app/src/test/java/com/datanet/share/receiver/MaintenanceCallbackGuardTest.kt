package com.datanet.share.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MaintenanceCallbackGuardTest {
    @Test fun failedStatsAndCongestionObserversDoNotBlockLaterMaintenanceReports() {
        var reports = 0
        repeat(3) {
            MaintenanceCallbackGuard.run {
                reports++
                throw IllegalStateException("test stats observer failure")
            }
            MaintenanceCallbackGuard.run {
                reports++
                throw java.io.IOException("test congestion observer failure")
            }
            MaintenanceCallbackGuard.run { reports++ }
        }
        assertEquals(9, reports)
    }

    @Test fun unexpectedTickExceptionRequestsFailClosedInsteadOfContinuing() {
        var failures = 0
        var tickCalls = 0
        val shouldContinue = MaintenanceCallbackGuard.runCriticalTick(
            tick = {
                tickCalls++
                throw IllegalStateException("private detail must not escape the tick")
            },
            onFailure = { failures++ }
        )
        assertEquals(false, shouldContinue)
        assertEquals(1, tickCalls)
        assertEquals(1, failures)

        assertEquals(true, MaintenanceCallbackGuard.runCriticalTick(
            tick = { tickCalls++ },
            onFailure = { failures++ }
        ))
        assertEquals(2, tickCalls)
        assertEquals(1, failures)
    }

    @Test fun fatalTickErrorsStillPropagateWithoutFalseRecovery() {
        var failures = 0
        try {
            MaintenanceCallbackGuard.runCriticalTick(
                tick = { throw AssertionError("fatal VM-level failure") },
                onFailure = { failures++ }
            )
            fail("fatal errors must not be converted into a recoverable VPN stop")
        } catch (_: AssertionError) {
            assertEquals(0, failures)
        }
    }

    @Test fun vmErrorsAreNotSilenced() {
        try {
            MaintenanceCallbackGuard.run { throw AssertionError("fatal") }
            fail("fatal errors must propagate")
        } catch (_: AssertionError) {
            // Expected: only ordinary callback exceptions are suppressed.
        }
    }
}

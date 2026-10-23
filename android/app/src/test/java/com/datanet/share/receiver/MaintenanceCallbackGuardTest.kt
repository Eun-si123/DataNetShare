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

    @Test fun vmErrorsAreNotSilenced() {
        try {
            MaintenanceCallbackGuard.run { throw AssertionError("fatal") }
            fail("fatal errors must propagate")
        } catch (_: AssertionError) {
            // Expected: only ordinary callback exceptions are suppressed.
        }
    }
}

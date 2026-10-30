package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class WifiPasswordPersistenceTest {
    @Test fun failedRestorePreservesRecordAndRetryCanSucceed() {
        var record: String? = "ExamplePassphrase!"
        var clearCalls = 0
        var loadAttempts = 0
        val load = {
            loadAttempts++
            if (loadAttempts == 1) throw IllegalStateException("keystore temporarily unavailable")
            record
        }
        val first = WifiPasswordPersistence.restore(load)
        assertTrue(first.failed)
        assertNull(first.password)
        assertFalse(WifiPasswordPersistence.persistOnStart(
            "", first.failed, { record = it }, { clearCalls++; record = null }))
        assertEquals(0, clearCalls)
        assertEquals("ExamplePassphrase!", record)

        val retry = WifiPasswordPersistence.restore(load)
        assertFalse(retry.failed)
        assertEquals("ExamplePassphrase!", retry.password)
        assertEquals("ExamplePassphrase!", record)
    }

    @Test fun malformedOrUndecryptableRecordIsNotDeletedImplicitly() {
        var clearCalls = 0
        val failed = WifiPasswordPersistence.restore {
            throw IllegalArgumentException("invalid encrypted record")
        }
        assertTrue(failed.failed)
        assertFalse(WifiPasswordPersistence.persistOnStart(
            "", failed.failed, { fail("Unexpected overwrite") }, { clearCalls++ }))
        assertEquals(0, clearCalls)
    }

    @Test fun deliberateReplacementAfterFailureAndClearAfterSuccessfulRestore() {
        var record: String? = "OldStoredPassphrase"
        var clears = 0
        assertTrue(WifiPasswordPersistence.persistOnStart(
            "NewStoredPassphrase", true, { record = it }, { clears++; record = null }))
        assertEquals("NewStoredPassphrase", record)
        assertEquals(0, clears)

        val restored = WifiPasswordPersistence.restore { record }
        assertFalse(restored.failed)
        assertTrue(WifiPasswordPersistence.persistOnStart(
            "", restored.failed, { fail("Unexpected save") }, { clears++; record = null }))
        assertNull(record)
        assertEquals(1, clears)
    }

    @Test fun missingRecordAllowsGeneratedPasswordMode() {
        var cleared = false
        val restored = WifiPasswordPersistence.restore { null }
        assertFalse(restored.failed)
        assertNull(restored.password)
        assertTrue(WifiPasswordPersistence.persistOnStart(
            "", restored.failed, { fail("Unexpected save") }, { cleared = true }))
        assertTrue(cleared)
    }

    @Test fun failedWriteDoesNotPretendToSucceed() {
        try {
            WifiPasswordPersistence.persistOnStart(
                "NewStoredPassphrase", true,
                { throw IllegalStateException("storage unavailable") }, { fail("Unexpected clear") })
            fail("Save failure must propagate to the existing UI error handler")
        } catch (_: IllegalStateException) {}
    }
}

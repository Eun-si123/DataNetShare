package com.datanet.share.common

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpTestWorkerLaunchTest {
    @Test fun rejectedWorkerLaunchReportsFailureAndFinishesOnce() {
        for (failure in listOf(IllegalThreadStateException("synthetic state private"), SecurityException("synthetic permission private"), OutOfMemoryError("synthetic capacity private"))) {
            var results = 0
            val errors = mutableListOf<String>()
            var finished = 0
            UdpTest.runTest(
                onResult = { results++ },
                onError = { errors += it },
                onFinished = { finished++ },
                launchWorker = { throw failure }
            )
            assertEquals(0, results)
            assertEquals(1, errors.size)
            assertEquals("UDP test could not start. Please retry.", errors.single())
            assertEquals(1, finished)
        }
    }

    @Test fun finishCallbackStillRunsIfErrorCallbackThrows() {
        var finished = 0
        try {
            UdpTest.runTest(
                onResult = {},
                onError = { throw IllegalStateException("synthetic callback failure") },
                onFinished = { finished++ },
                launchWorker = { throw IllegalThreadStateException() }
            )
            throw AssertionError("Expected callback exception")
        } catch (expected: IllegalStateException) {
            assertEquals("synthetic callback failure", expected.message)
        }
        assertEquals(1, finished)
    }

    @Test fun successfulLaunchDoesNotFinishUntilWorkerRuns() {
        var launches = 0
        var finished = 0
        UdpTest.runTest(
            onResult = {},
            onError = {},
            onFinished = { finished++ },
            launchWorker = { launches++ } // Deliberately do not run the network task.
        )
        assertEquals(1, launches)
        assertEquals(0, finished)
    }
}

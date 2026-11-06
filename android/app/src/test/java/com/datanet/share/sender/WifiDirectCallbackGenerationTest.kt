package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test

/** These invoke the same delegate as WifiDirectHost's real ActionListener. */
class WifiDirectCallbackGenerationTest {
    private class FakeManager {
        var listener: WifiDirectCreateCallbacks? = null
        fun request(callbacks: WifiDirectCreateCallbacks) { listener = callbacks }
        fun succeed() { listener!!.onSuccess() }
        fun fail(reason: Int) { listener!!.onFailure(reason) }
    }

    @Test fun stopBeforeCreateSuccessSuppressesTheOldActionListener() {
        val gate = WifiDirectCallbackGeneration()
        val token = gate.start()
        val manager = FakeManager()
        var accepted = 0
        var failures = 0
        manager.request(WifiDirectCreateCallbacks(gate, token,
            onAccepted = { accepted++ }, onRejected = { failures++ }))
        gate.stop() // Old Android request may complete after channel close.
        manager.succeed()
        manager.fail(2)
        assertFalse(gate.running)
        assertEquals(0, accepted)
        assertEquals(0, failures)
    }

    @Test fun delayedOldManagerFailureCannotInterfereWithRestartedSession() {
        val gate = WifiDirectCallbackGeneration()
        val firstToken = gate.start()
        val oldManager = FakeManager()
        var oldAccepted = 0
        var oldRejected = 0
        oldManager.request(WifiDirectCreateCallbacks(gate, firstToken,
            { oldAccepted++ }, { oldRejected++ }))
        gate.stop()
        val newToken = gate.start()
        val newManager = FakeManager()
        var newAccepted = 0
        var newRejectedReason = -1
        newManager.request(WifiDirectCreateCallbacks(gate, newToken,
            { newAccepted++ }, { newRejectedReason = it }))
        oldManager.fail(2) // BUSY from old channel should not trigger retry.
        oldManager.succeed()
        assertEquals(0, oldRejected)
        assertEquals(0, oldAccepted)
        assertEquals(-1, newRejectedReason)
        assertEquals(0, newAccepted)
        newManager.succeed()
        assertEquals(1, newAccepted)
        newManager.fail(3)
        assertEquals(3, newRejectedReason)
        assertNotEquals(firstToken, newToken)
    }

    @Test fun currentListenerReceivesSuccessAndFailureUntilStop() {
        val gate = WifiDirectCallbackGeneration()
        val token = gate.start()
        val manager = FakeManager()
        var accepted = 0
        val rejected = mutableListOf<Int>()
        manager.request(WifiDirectCreateCallbacks(gate, token,
            { accepted++ }, { rejected += it }))
        manager.succeed()
        manager.fail(2)
        assertEquals(1, accepted)
        assertEquals(listOf(2), rejected)
        gate.stop()
        manager.succeed()
        manager.fail(3)
        assertEquals(1, accepted)
        assertEquals(listOf(2), rejected)
    }

    @Test fun duplicateStartMustNotReuseCurrentCallbackToken() {
        val gate = WifiDirectCallbackGeneration()
        val first = gate.start()
        assertThrows(IllegalStateException::class.java) { gate.start() }
        assertTrue(gate.accepts(first))
        assertEquals(first, gate.generation)
        gate.stop()
        gate.stop()
        assertFalse(gate.accepts(first))
    }
}

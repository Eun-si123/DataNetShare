package com.datanet.share.common

import org.junit.Assert.*
import org.junit.Test

class TcpSequenceWindowTest {
    @Test fun inOrderPayloadAdvancesExpectedSequence() {
        assertEquals(104L, TcpSequenceWindow.advanceIfInOrder(100L, 100, 4))
    }
    @Test fun duplicatePacketIsRejected() {
        assertNull(TcpSequenceWindow.advanceIfInOrder(104L, 100, 4))
    }
    @Test fun outOfOrderPacketIsRejected() {
        assertNull(TcpSequenceWindow.advanceIfInOrder(100L, 110, 4))
    }
    @Test fun zeroLengthPayloadIsRejected() {
        assertNull(TcpSequenceWindow.advanceIfInOrder(100L, 100, 0))
    }
    @Test fun sequenceNumberWrapsAt32Bits() {
        assertEquals(2L, TcpSequenceWindow.advanceIfInOrder(0xFFFFFFFEL, -2, 4))
    }
}

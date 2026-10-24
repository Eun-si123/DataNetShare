package com.datanet.share.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpTestResponseTest {
    private val transaction = ByteArray(12) { (it + 1).toByte() }

    private fun response(attributes: ByteArray = ByteArray(0)): ByteArray =
        ByteArray(20 + attributes.size).apply {
            this[0] = 1; this[1] = 1
            this[2] = (attributes.size ushr 8).toByte()
            this[3] = attributes.size.toByte()
            this[4] = 0x21; this[5] = 0x12
            this[6] = 0xa4.toByte(); this[7] = 0x42
            transaction.copyInto(this, 8)
            attributes.copyInto(this, 20)
        }

    @Test fun acceptsFramedBindingSuccessWithMatchingTransaction() {
        assertTrue(UdpTest.isValidBindingResponse(response(), transaction))
        // Framed XOR-MAPPED-ADDRESS attribute; this test validates framing, not NAT mapping.
        val attribute = byteArrayOf(0, 0x20, 0, 8, 0, 1, 0, 0, 0, 0, 0, 0)
        assertTrue(UdpTest.isValidBindingResponse(response(attribute), transaction))
    }

    @Test fun rejectsTruncatedExtraAndInconsistentResponseLengths() {
        val valid = response(ByteArray(8))
        for (bad in listOf(
            valid.copyOf(19),
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            valid.copyOf().apply { this[3] = 4 },
            valid.copyOf().apply { this[3] = 7 },
            valid.copyOf().apply { this[2] = 1 }
        )) {
            assertFalse(UdpTest.isValidBindingResponse(bad, transaction))
        }
    }

    @Test fun rejectsWrongTypeCookieOrTransaction() {
        val valid = response()
        for (bad in listOf(
            valid.copyOf().apply { this[1] = 0 },
            valid.copyOf().apply { this[4] = 0 },
            valid.copyOf().apply { this[19] = 0 }
        )) {
            assertFalse(UdpTest.isValidBindingResponse(bad, transaction))
        }
        assertFalse(UdpTest.isValidBindingResponse(valid, ByteArray(11)))
    }
}

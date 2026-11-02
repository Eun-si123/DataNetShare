package com.datanet.share.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpTestResponseTest {
    private val transaction = ByteArray(12) { (it + 1).toByte() }
    private val mappedIpv4 = byteArrayOf(0, 0x20, 0, 8, 0, 1, 0, 0, 0, 0, 0, 0)
    private val mappedIpv6 = byteArrayOf(0, 0x20, 0, 20, 0, 2, 0, 0) + ByteArray(16)

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
        assertTrue(UdpTest.isValidBindingResponse(response(mappedIpv4), transaction))
        assertTrue(UdpTest.isValidBindingResponse(response(mappedIpv6), transaction))
    }

    @Test fun rejectsBindingSuccessWithoutValidXorMappedAddress() {
        val optional = byteArrayOf(0x80.toByte(), 0x24, 0, 1, 0x7f, 0, 0, 0)
        assertFalse(UdpTest.isValidBindingResponse(response(), transaction))
        assertFalse(UdpTest.isValidBindingResponse(response(optional), transaction))
        assertFalse(UdpTest.isValidBindingResponse(response(mappedIpv4.copyOf().apply { this[5] = 3 }), transaction))
        assertFalse(UdpTest.isValidBindingResponse(response(mappedIpv4.copyOf().apply { this[5] = 2 }), transaction))
        assertFalse(UdpTest.isValidBindingResponse(response(mappedIpv4.copyOf().apply { this[3] = 4 }), transaction))
    }

    @Test fun rejectsTruncatedExtraAndInconsistentResponseLengths() {
        val valid = response(mappedIpv4)
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

    @Test fun acceptsUnknownAttributesWithNonzeroPadding() {
        // Unknown attributes are permitted; padding bytes need not be zero.
        val unknown = byteArrayOf(0x80.toByte(), 0x24, 0, 1, 0x7f, 0x55, 0x66, 0x77)
        assertTrue(UdpTest.isValidBindingResponse(response(unknown + mappedIpv4), transaction))
        assertTrue(UdpTest.isValidBindingResponse(response(mappedIpv4 + unknown), transaction))
    }

    @Test fun rejectsMalformedAttributeLengthsAndNestedHeaders() {
        val valid = response(mappedIpv4)
        for (bad in listOf(
            valid.copyOf().apply { this[23] = 12 }, // value length exceeds remaining bytes
            response(byteArrayOf(0, 0x20, 0, 5, 0, 1, 0, 0)), // padding exceeds packet
            response(byteArrayOf(0, 0x20, 0, 0, 1, 2, 3, 4)) // second attribute is truncated
        )) {
            assertFalse(UdpTest.isValidBindingResponse(bad, transaction))
        }
    }

    @Test fun rejectsWrongTypeCookieOrTransaction() {
        val valid = response(mappedIpv4)
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

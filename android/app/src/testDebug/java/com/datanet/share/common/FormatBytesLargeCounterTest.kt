package com.datanet.share.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatBytesLargeCounterTest {
    @Test fun largeSessionCountersUseReadableBinaryUnits() {
        assertEquals("1.00GB", FormatBytes.format(1_073_741_824L))
        assertEquals("1.50GB", FormatBytes.format(1_610_612_736L))
        assertEquals("1.00TB", FormatBytes.format(1_099_511_627_776L))
        assertEquals("rx=1.00GB tx=1.00TB", FormatBytes.formatRxTx(1_073_741_824L, 1_099_511_627_776L))
    }

    @Test fun existingByteKilobyteAndMegabyteLabelsRemainUnchanged() {
        assertEquals("0B", FormatBytes.format(0))
        assertEquals("1023B", FormatBytes.format(1023))
        assertEquals("1.00KB", FormatBytes.format(1024))
        assertEquals("1.00MB", FormatBytes.format(1_048_576))
    }
}

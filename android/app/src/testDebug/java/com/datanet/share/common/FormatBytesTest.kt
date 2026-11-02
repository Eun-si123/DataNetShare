package com.datanet.share.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatBytesTest {
    @Test fun byteUnitsUseStableDecimalSeparatorAcrossDeviceLocales() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.FRANCE, Locale.GERMANY)) {
                Locale.setDefault(locale)
                assertEquals("locale=$locale", "1.50KB", FormatBytes.format(1536))
                assertEquals("locale=$locale", "2.25MB", FormatBytes.format(2_359_296))
                assertEquals("locale=$locale", "rx=1.50KB tx=2.25MB",
                    FormatBytes.formatRxTx(1536, 2_359_296))
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}

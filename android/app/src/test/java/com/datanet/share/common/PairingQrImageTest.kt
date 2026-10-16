package com.datanet.share.common

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingQrImageTest {
    @Test fun generatedQrImageCanBeDecodedWithoutLosingWiFiOrProxyDetails() {
        val pairing = PairingPayload(
            host = "192.168.49.1",
            port = 8282,
            password = "0123456789abcdef0123456789abcdef",
            ssid = "DIRECT-한글 테스트 QR",
            wifiPassword = "wifi#pass%?_=12"
        )
        val encoded = pairing.encode()
        val side = 600
        val qr = QRCodeWriter().encode(encoded, BarcodeFormat.QR_CODE, side, side)
        val pixels = IntArray(side * side) { offset ->
            if (qr[offset % side, offset / side]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        val scanned = QRCodeReader().decode(bitmap).text
        assertEquals(pairing, PairingPayload.parse(scanned))
    }
}

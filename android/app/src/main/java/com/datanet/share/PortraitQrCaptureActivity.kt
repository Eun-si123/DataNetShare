package com.datanet.share

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * ZXing defaults its scanner screen orientation separately from MainActivity.
 * A dedicated manifest-declared portrait Activity keeps QR scanning upright.
 */
class PortraitQrCaptureActivity : CaptureActivity()

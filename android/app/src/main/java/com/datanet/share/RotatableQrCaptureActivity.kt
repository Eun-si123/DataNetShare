package com.datanet.share

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The scanner starts in the user's current UI orientation.
 * Its manifest uses fullUser so portrait and landscape are both supported
 * when Android automatic rotation is enabled; no forced landscape or portrait.
 */
class RotatableQrCaptureActivity : CaptureActivity()

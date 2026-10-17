package com.datanet.share.sender

/**
 * Wi-Fi Direct group-owner options supported by Android's public API (29+).
 * This configures the P2P *link* only, not cellular upstream, normal Android
 * tethering, hotspot WPA mode, 802.11ax/be, channels or packet-level QoS.
 *
 * A custom Wi-Fi Direct passphrase is remembered encrypted by WifiPasswordVault
 * (Android Keystore); it is never logged or saved as plaintext. Blank means
 * Android generates a passphrase in legacy mode, or we generate a new random
 * passphrase when other group options are customized.
 */
data class WifiDirectProfile(
    val nameSuffix: String = "",
    val passphrase: String = "",
    val band: Band = Band.AUTO
) {
    enum class Band { AUTO, GHZ_2_4, GHZ_5 }

    fun isCustomized(): Boolean =
        nameSuffix.isNotEmpty() || passphrase.isNotEmpty() || band != Band.AUTO

    fun validate(): String? {
        // Keep the complete DIRECT-xy SSID within 32 bytes, even on devices
        // with conservative P2P group name validation.
        if (nameSuffix.length > 22 || !nameSuffix.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }) return "SSID suffix: use up to 22 letters, digits, hyphen or underscore"
        if (passphrase.isNotEmpty() &&
            (passphrase.length !in 8..63 || passphrase.any { it.code !in 32..126 }))
            return "Wi-Fi Direct password: use 8–63 printable ASCII characters"
        return null
    }

    fun networkName(twoAlnumChars: String): String {
        require(twoAlnumChars.length == 2 && twoAlnumChars.all { it.isLetterOrDigit() && it.code < 128 })
        require(validate() == null)
        return "DIRECT-$twoAlnumChars$nameSuffix"
    }
}

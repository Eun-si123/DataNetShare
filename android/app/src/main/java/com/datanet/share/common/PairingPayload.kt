package com.datanet.share.common

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Private in-person handoff format, not an Android deep link or a long-lived invite.
 * Contains plaintext Wi-Fi and SOCKS credentials; never log or persist its contents.
 *
 * Random proxy passwords rotate on restart. Reusing an explicit custom password
 * can keep old QR authentication valid across sessions. This is NOT a single-scan
 * token: someone who photographs the QR can reuse its still-valid credentials.
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val password: String,
    val ssid: String = "",
    val wifiPassword: String = ""
) {
    // Data-class generated toString() would expose plaintext SOCKS/Wi-Fi credentials,
    // SSID and peer address in exception messages or accidental debug logging.
    // encode() is the only intentional QR serialization path.
    override fun toString(): String = "PairingPayload(<redacted>)"

    fun encode(): String {
        require(isValid()) { "Invalid session pairing details" }
        return buildString {
            append(PREFIX)
            append("h=").append(escape(host))
            append("&p=").append(port)
            append("&pw=").append(escape(password))
            if (ssid.isNotEmpty()) {
                append("&s=").append(escape(ssid))
                append("&w=").append(escape(wifiPassword))
            }
        }
    }

    // QR must not accept Wi-Fi values that PeerWifiConnector.request will reject.
    private fun isValid(): Boolean =
        Endpoint.isIpv4(host) && port in 1..65535 &&
            ProxyPasswordPolicy.valid(password) &&
            ((ssid.isEmpty() && wifiPassword.isEmpty()) ||
                (ssid.toByteArray(Charsets.UTF_8).size in 1..32 &&
                    ssid.none { Character.isISOControl(it) } &&
                    wifiPassword.length in 8..63 && wifiPassword.all { it.code in 32..126 }))

    companion object {
        private const val PREFIX = "datanetshare://pair/v1?"
        private const val MAX_QR_LENGTH = 1024
        private val FIELDS = setOf("h", "p", "pw", "s", "w")
        private fun escape(value: String) = URLEncoder.encode(value, "UTF-8")
        private val percentRun = Regex("(?:%[0-9A-Fa-f]{2})+")

        /** URLDecoder silently replaces malformed UTF-8 with U+FFFD; reject corrupt QR bytes. */
        private fun decodeStrict(raw: String): String {
            percentRun.findAll(raw).forEach { run ->
                val bytes = ByteArray(run.value.length / 3) { index ->
                    run.value.substring(index * 3 + 1, index * 3 + 3).toInt(16).toByte()
                }
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
            }
            return URLDecoder.decode(raw, "UTF-8")
        }

        /**
         * Reject other QR schemes, duplicate keys, unknown fields and malformed
         * percent escapes instead of accidentally accepting arbitrary scanner input.
         */
        fun parse(raw: String): PairingPayload? = runCatching {
            require(raw.length <= MAX_QR_LENGTH && raw.startsWith(PREFIX))
            val uri = URI(raw)
            require(uri.scheme == "datanetshare" && uri.host == "pair" &&
                uri.rawPath == "/v1" && uri.rawFragment == null &&
                uri.rawUserInfo == null && uri.port == -1)
            val values = linkedMapOf<String, String>()
            for (part in (uri.rawQuery ?: error("Missing pairing details")).split('&')) {
                val sep = part.indexOf('=')
                require(sep > 0)
                val key = part.substring(0, sep)
                require(key in FIELDS && !values.containsKey(key))
                val value = decodeStrict(part.substring(sep + 1))
                require(value.none { it == '\u0000' })
                values[key] = value
            }
            require(values.keys.containsAll(setOf("h", "p", "pw")))
            val payload = PairingPayload(
                values.getValue("h"),
                values.getValue("p").toInt(),
                values.getValue("pw"),
                values["s"] ?: "",
                values["w"] ?: ""
            )
            require(payload.isValid())
            payload
        }.getOrNull()
    }
}

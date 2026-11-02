package com.datanet.share.common

/** Numeric IPv4 sender endpoint; loopback remains valid for local tests. */
object Endpoint {
    fun isIpv4(host: String): Boolean {
        // Canonical dotted IPv4 is 7..15 ASCII chars. Reject oversized input
        // before split() allocates a token for every separator on the UI path.
        if (host.length !in 7..15) return false
        val parts = host.split('.')
        if (parts.size != 4 || !parts.all { part ->
                part.isNotEmpty() && part.all { it in '0'..'9' } &&
                    part.toIntOrNull()?.let { it in 0..255 } == true &&
                    (part == "0" || !part.startsWith('0'))
            }) return false
        // Reject 0/8 (this network), 224/4 (multicast) and 240/4
        // (reserved/broadcast); retain 127/8 for local loopback tests.
        val first = parts[0].toInt()
        return first in 1..223
    }
}

package com.datanet.share.common

/** Numeric IPv4 unicast address usable as a remote sender endpoint. */
object Endpoint {
    fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || !parts.all { part ->
                part.isNotEmpty() && part.all { it in '0'..'9' } &&
                    part.toIntOrNull()?.let { it in 0..255 } == true &&
                    (part == "0" || !part.startsWith('0'))
            }) return false
        // A peer cannot live in 0/8 (this network), 127/8 (loopback),
        // 224/4 (multicast) or 240/4 (reserved/broadcast).
        val first = parts[0].toInt()
        return first in 1..126 || first in 128..223
    }
}

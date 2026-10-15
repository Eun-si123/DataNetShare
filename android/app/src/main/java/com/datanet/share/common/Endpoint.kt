package com.datanet.share.common

object Endpoint {
    fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } &&
                part.toIntOrNull()?.let { it in 0..255 } == true && (part == "0" || !part.startsWith('0'))
        } && host != "0.0.0.0" && host != "255.255.255.255"
    }
}

package com.datanet.share.common

/** Custom SOCKS5 passwords are temporary and never stored in preferences. */
object ProxyPasswordPolicy {
    fun valid(password: String): Boolean =
        password.length in 12..128 && password.all { it.code in 33..126 }
}

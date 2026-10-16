package com.datanet.share.receiver

/**
 * Nonblocking TUN writes can transiently return EAGAIN/EINTR under bursty
 * browser traffic. Retry only a few times after a bounded POLLOUT wait.
 * This keeps the VPN packet loop responsive while avoiding an immediate
 * packet drop on the first congested write.
 *
 * [write] returns true only for a complete packet write; false denotes an
 * interrupted/unavailable FD. Fatal errors are propagated to the caller.
 */
internal object TunWriteRetry {
    fun attempt(maxAttempts: Int = 4, write: () -> Boolean, awaitWritable: () -> Boolean): Boolean {
        require(maxAttempts in 1..8)
        repeat(maxAttempts) { index ->
            if (write()) return true
            if (index + 1 < maxAttempts && !awaitWritable()) return false
        }
        return false
    }
}

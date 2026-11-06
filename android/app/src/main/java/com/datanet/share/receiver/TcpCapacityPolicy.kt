package com.datanet.share.receiver

/**
 * Each receiver TCP/UDP flow occupies at least one sender SOCKS connection.
 * Reserve eight slots of the 128-client sender budget for health/control probes.
 * A burst of TCP can borrow headroom while there are few UDP associations.
 * UDP and TCP admissions both enforce the combined budget.
 */
internal object TcpCapacityPolicy {
    const val TOTAL_BUDGET = 120
    const val MAX_TCP = 112
    const val MAX_UDP = 40
    const val IDLE_RECLAIM_NANOS = 20_000_000_000L
    const val HALF_OPEN_RECLAIM_NANOS = 6_000_000_000L
    const val IDLE_TIMEOUT_NANOS = 90_000_000_000L
    const val HALF_OPEN_TIMEOUT_NANOS = 10_000_000_000L

    fun allowTcp(tcp: Int, udp: Int): Boolean =
        tcp >= 0 && udp >= 0 && tcp < MAX_TCP && udp < TOTAL_BUDGET - tcp
    fun allowUdp(tcp: Int, udp: Int): Boolean =
        tcp >= 0 && udp >= 0 && udp < MAX_UDP && tcp < TOTAL_BUDGET - udp

    fun evictIdle(idleNanos: Long, handshakeComplete: Boolean): Boolean =
        idleNanos >= if (handshakeComplete) IDLE_RECLAIM_NANOS else HALF_OPEN_RECLAIM_NANOS
}

package com.datanet.share.receiver

/**
 * Browser QUIC and DNS can open many short-lived UDP flows simultaneously.
 * Keep a bounded headroom above the old 32-flow limit, leaving eight sender slots free for health checks, and reclaim only
 * substantially idle associations when admission would otherwise fail.
 *
 * Each association owns sockets and threads: an unbounded limit would
 * exacerbate slow phones' CPU/memory pressure instead of increasing speed.
 */
internal object UdpFlowPolicy {
    const val MAX_FLOWS = 40
    const val EVICT_IDLE_NANOS = 15_000_000_000L

    fun shouldEvictAtCapacity(active: Int, idleNanos: Long): Boolean =
        active >= MAX_FLOWS && idleNanos >= EVICT_IDLE_NANOS
}

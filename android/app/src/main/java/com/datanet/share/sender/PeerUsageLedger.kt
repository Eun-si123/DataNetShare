package com.datanet.share.sender

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ephemeral, per-remote-IP SOCKS traffic accounting. A peer is an authenticated
 * client IP, not an Android device identity; multiple apps share one peer.
 * No SSID, MAC, destination IP, URL or long-term traffic history is stored.
 * Counts application payload bytes actually relayed, not radio overhead.
 */
class PeerUsageLedger {
    data class Snapshot(val ip: String, val activeConnections: Int,
                        val rxBytes: Long, val txBytes: Long)
    class Entry internal constructor(val ip: String) {
        private val active = AtomicInteger()
        private val rx = AtomicLong()
        private val tx = AtomicLong()

        internal fun opened() = active.incrementAndGet()
        internal fun closed() = active.updateAndGet { (it - 1).coerceAtLeast(0) }
        fun received(bytes: Int) { if (bytes > 0) rx.addAndGet(bytes.toLong()) }
        fun sent(bytes: Int) { if (bytes > 0) tx.addAndGet(bytes.toLong()) }
        internal fun snapshot() = Snapshot(ip, active.get(), rx.get(), tx.get())
    }
    private val peers = ConcurrentHashMap<String, Entry>()

    /** Protect memory on unexpectedly large untrusted sender interfaces. */
    @Synchronized fun open(ip: String): Entry? {
        val existing = peers[ip]
        if (existing != null) { existing.opened(); return existing }
        if (peers.size >= 32) return null
        val created = Entry(ip)
        peers[ip] = created
        created.opened()
        return created
    }
    fun close(entry: Entry?) { entry?.closed() }
    fun snapshot(): List<Snapshot> =
        peers.values.map { it.snapshot() }.sortedWith(
            compareByDescending<Snapshot> { it.activeConnections }
                .thenByDescending { it.rxBytes + it.txBytes }.thenBy { it.ip })
    fun clear() { peers.clear() }
}

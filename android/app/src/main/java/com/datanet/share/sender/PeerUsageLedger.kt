package com.datanet.share.sender

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    /** One lease per authenticated connection, sharing counters for the same IP. */
    class Entry internal constructor(private val totals: Totals) {
        val ip: String get() = totals.ip
        private val released = AtomicBoolean()
        internal fun closed() {
            if (released.compareAndSet(false, true)) totals.closed()
        }
        fun received(bytes: Int) { totals.received(bytes) }
        fun sent(bytes: Int) { totals.sent(bytes) }
    }

    internal class Totals(val ip: String) {
        private val active = AtomicInteger()
        private val rx = AtomicLong()
        private val tx = AtomicLong()

        // Accessed only while the ledger's open() monitor is held.
        var lastOpenedOrder = 0L
        fun opened(order: Long) { lastOpenedOrder = order; active.incrementAndGet() }
        fun isIdle() = active.get() == 0
        fun closed() { active.decrementAndGet() }
        fun received(bytes: Int) { if (bytes > 0) rx.addAndGet(bytes.toLong()) }
        fun sent(bytes: Int) { if (bytes > 0) tx.addAndGet(bytes.toLong()) }
        fun snapshot() = Snapshot(ip, active.get(), rx.get(), tx.get())
    }

    private val peers = ConcurrentHashMap<String, Totals>()
    private var nextOpenOrder = 0L

    /** Keep active peers tracked, reusing an inactive slot after 32 distinct IPs. */
    @Synchronized fun open(ip: String): Entry? {
        val existing = peers[ip]
        if (existing != null) {
            existing.opened(++nextOpenOrder)
            return Entry(existing)
        }
        if (peers.size >= 32) {
            // Never evict an active connection just to display a newer peer.
            val oldestIdle = peers.values.filter { it.isIdle() }
                .minByOrNull { it.lastOpenedOrder } ?: return null
            peers.remove(oldestIdle.ip, oldestIdle)
        }
        val created = Totals(ip)
        peers[ip] = created
        created.opened(++nextOpenOrder)
        return Entry(created)
    }
    fun close(entry: Entry?) { entry?.closed() }
    fun snapshot(): List<Snapshot> =
        peers.values.map { it.snapshot() }.sortedWith(
            compareByDescending<Snapshot> { it.activeConnections }
                .thenByDescending { it.rxBytes + it.txBytes }.thenBy { it.ip })
    @Synchronized fun clear() { peers.clear(); nextOpenOrder = 0L }
}

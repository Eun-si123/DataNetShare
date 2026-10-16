package com.datanet.share.common

/**
 * Private, process-only diagnostic categories. Do not store endpoints, QR data,
 * IPs, domains, SOCKS credentials, payload bytes or arbitrary exception messages.
 *
 * Counters are bounded so a noisy connection cannot consume unlimited memory.
 */
enum class NetworkEvent(val description: String) {
    SENDER_UPSTREAM("Sender could not open an Internet connection"),
    SENDER_CLIENT_LIMIT("Sender reached its concurrent-client limit"),
    SENDER_IDLE_RECLAIMED("Sender reclaimed idle TCP connections"),
    TCP_UPSTREAM("Receiver TCP upstream connection failed/reset"),
    TCP_UPLOAD("Receiver TCP upload failed"),
    TCP_IDLE("Receiver TCP flow timed out"),
    TCP_RETRANSMIT("Receiver TCP retransmissions exhausted"),
    TCP_FLOW_LIMIT("Receiver TCP capacity reached (80 concurrent flows)"),
    UDP_FLOW_LIMIT("Receiver UDP capacity reached (32 concurrent flows)"),
    UDP_SETUP("Receiver UDP proxy setup failed"),
    UDP_RELAY("Receiver UDP relay failed"),
    TUN_CONGESTION("VPN tunnel output temporarily congested"),
    TUN_PACKET("VPN packet loop failed"),
}

class DiagnosticJournal(private val maxKinds: Int = 12) {
    private val counts = LinkedHashMap<NetworkEvent, Int>()
    init { require(maxKinds in 1..NetworkEvent.entries.size) }
    @Synchronized fun record(kind: NetworkEvent): String {
        val current = counts.remove(kind) ?: 0
        if (counts.size >= maxKinds) counts.remove(counts.keys.first())
        counts[kind] = if (current == Int.MAX_VALUE) current else current + 1
        return snapshotLocked()
    }
    @Synchronized fun clear(): String {
        counts.clear()
        return snapshotLocked()
    }
    @Synchronized fun snapshot(): String = snapshotLocked()
    private fun snapshotLocked(): String = if (counts.isEmpty()) {
        "No internal network failures recorded. This does not confirm Internet connectivity."
    } else {
        counts.entries.reversed().joinToString("\n") { (kind, total) ->
            "${kind.description} ×$total"
        }
    }
}

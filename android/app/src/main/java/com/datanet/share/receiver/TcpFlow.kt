package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.IpPackets
import com.datanet.share.common.Ipv4Packet
import com.datanet.share.common.Socks5
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.ArrayDeque

/** Bounded IPv4 TCP bridge. Deliberately experimental; see docs/TESTING.md. */
class TcpFlow(
    val key: FlowKey,
    initialAppSequence: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val password: String,
    private val protect: (Socket) -> Boolean,
    private val writePacket: (ByteArray) -> Unit,
    private val onRx: (Int) -> Unit,
    private val onTx: (Int) -> Unit,
    private val onClose: () -> Unit,
    initialSequence: Int = SecureRandom().nextInt(),
    private val onError: (String) -> Unit = {},
    private val onConnected: () -> Unit = {},
    // Aggregate only; no destinations, packets or credentials in this callback.
    private val onRetransmit: () -> Unit = {},
    // SYN-advertised peer MSS. Must not transmit larger segments than peer accepts.
    peerMss: Int = 1460,
    // Negotiated only for a SYN containing RFC 7323 WSopt.
    peerWindowScale: Int? = null
) {
    companion object {
        private const val LEGACY_CAPACITY = 65_535
        // Bound each scaled flow to about 256 KiB: 112 flows < 29 MiB
        // of TCP upload buffering even at full utilization.
        private const val WINDOW_SHIFT = 2
        private const val SCALED_CAPACITY = LEGACY_CAPACITY shl WINDOW_SHIFT
        private const val MSS = 1400
        // RFC 793/879: explicitly advertise the segment size that our TUN bridge
        // can handle without fragmentation, instead of relying on 536-byte fallback.
        private val SYN_MSS_OPTION = byteArrayOf(2, 4, (MSS ushr 8).toByte(), MSS.toByte())
        // MSS(4) + WSopt(3) + NOP(1): properly aligned.
        private val SYN_SCALED_OPTION = SYN_MSS_OPTION +
            byteArrayOf(3, 3, WINDOW_SHIFT.toByte(), 1)
        private const val MASK = 0xffffffffL
        private fun unsigned(value: Int) = value.toLong() and MASK
        private fun distance(from: Long, to: Long) = (to - from) and MASK
    }
    private val transmitMss = minOf(MSS, peerMss.coerceAtLeast(1))
    private val peerScale = peerWindowScale?.coerceIn(0, 14)
    private val capacity = if (peerScale != null) SCALED_CAPACITY else LEGACY_CAPACITY
    // SYN windows are always unscaled; post-handshake advertised windows
    // are quantized to 4-byte units if we offered scale=2.
    private fun advertisedWindow(syn: Boolean = false): Int {
        val remaining = (capacity - queuedBytes).coerceAtLeast(0)
        return if (peerScale != null && !syn)
            (remaining ushr WINDOW_SHIFT).coerceAtMost(65_535)
        else remaining.coerceAtMost(65_535)
    }
    private fun peerAdvertisedWindow(raw: Int): Int =
        (raw.toLong() shl (peerScale ?: 0)).coerceAtMost(SCALED_CAPACITY.toLong()).toInt()
    private data class Segment(val sequence: Long, val flags: Int, val payload: ByteArray,
                               var sent: Long, var attempts: Int = 0) {
        val length: Int get() = payload.size + (if (flags and 3 != 0) 1 else 0)
    }
    private data class Write(val payload: ByteArray?, val fin: Boolean = false)
    private val lock = Object()
    private val pending = ArrayDeque<Segment>()
    private val writes = ArrayDeque<Write>()
    private var queuedBytes = 0
    private var expected = (unsigned(initialAppSequence) + 1) and MASK
    private var next = unsigned(initialSequence)
    private var acknowledged = next
    private var peerWindow = LEGACY_CAPACITY
    // Preserve the wire field for duplicate-ACK detection. The scaled/capped
    // byte window is a different unit and can hide real window updates.
    private var peerWireWindow = LEGACY_CAPACITY
    private var appFin = false
    private var outputFinished = false
    private var remoteFin = false
    private var finAcknowledged = false
    private var established = false
    private var lastActivity = System.nanoTime()
    private var createdAt = lastActivity
    private var lastProbe = 0L
    private var duplicateAcks = 0
    @Volatile private var closed = false
    private var socket: Socket? = null

    fun start() = synchronized(lock) { if (!closed) sendTracked(0x12, ByteArray(0)) }
    fun retransmitSyn() = synchronized(lock) { pending.firstOrNull { it.flags and 2 != 0 }?.let(::emit) }

    /**
     * Capacity pressure must never indiscriminately kill active downloads.
     * A half-open SYN can be retired sooner, while established flows must have
     * no unacknowledged data, queued writes, or activity for a meaningful period.
     */
    fun reclaimableForCapacity(now: Long = System.nanoTime()): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (!established) return@synchronized now - createdAt >= TcpCapacityPolicy.HALF_OPEN_RECLAIM_NANOS
        now - lastActivity >= TcpCapacityPolicy.IDLE_RECLAIM_NANOS &&
            pending.isEmpty() && writes.isEmpty() && queuedBytes == 0
    }

    fun receive(packet: Ipv4Packet) = synchronized(lock) {
        if (closed) return@synchronized
        lastActivity = System.nanoTime()
        if (packet.flags and 0x10 != 0) {
            val ack = unsigned(packet.acknowledgement)
            if (distance(acknowledged, ack) <= distance(acknowledged, next)) {
                val previousAck = acknowledged
                val previousWireWindow = peerWireWindow
                acknowledged = ack; peerWireWindow = packet.window
                peerWindow = peerAdvertisedWindow(packet.window)
                while (pending.isNotEmpty()) {
                    val segment = pending.first()
                    if (distance(segment.sequence, ack) < segment.length || distance(segment.sequence, ack) >= 0x80000000L) break
                    pending.removeFirst()
                    if (segment.flags and 2 != 0) established = true
                    if (segment.flags and 1 != 0) finAcknowledged = true
                }
                // Fast-retransmit only once on the third true duplicate ACK
                // for the oldest outstanding data segment. This avoids waiting
                // for the 500ms RTO when Android has received later packets.
                // Window updates, SYN/FIN ACKs and payload packets do not count.
                val oldest = pending.firstOrNull()
                val isDuplicate = established && ack == previousAck &&
                    packet.window == previousWireWindow && packet.payload.isEmpty() &&
                    packet.flags and 3 == 0 &&
                    oldest != null && oldest.payload.isNotEmpty()
                duplicateAcks = if (isDuplicate) (duplicateAcks + 1).coerceAtMost(4) else 0
                if (duplicateAcks == 3 && oldest != null) {
                    oldest.sent = System.nanoTime()
                    oldest.attempts++
                    onRetransmit()
                    emit(oldest)
                }
                lock.notifyAll()
            }
        }
        if (established && !appFin) {
            val sequence = unsigned(packet.sequence)
            val overlap = distance(sequence, expected)
            val data = if (overlap <= packet.payload.size) packet.payload.copyOfRange(overlap.toInt(), packet.payload.size) else null
            if (data != null && data.isNotEmpty() && queuedBytes + data.size <= capacity) {
                writes.add(Write(data)); queuedBytes += data.size
                expected = (expected + data.size) and MASK; onTx(data.size); lock.notifyAll()
            }
            val finSequence = (sequence + packet.payload.size) and MASK
            if (packet.flags and 1 != 0 && finSequence == expected) {
                expected = (expected + 1) and MASK; appFin = true
                writes.add(Write(null, true)); lock.notifyAll()
            }
        }
        if (packet.payload.isNotEmpty() || packet.flags and 1 != 0) sendAck()
        if (appFin && finAcknowledged && outputFinished) close()
    }

    fun openUpstream() {
        val remote = Socket()
        // A SYN does not prove that the client completed the TCP handshake.
        // Until the peer ACKs our SYN-ACK, do not consume a sender SOCKS5 slot.
        synchronized(lock) {
            while (!closed && !established) lock.wait(1000)
            if (closed) { remote.close(); return }
            socket = remote
        }
        try {
            if (!protect(remote)) throw IOException("Could not protect proxy socket")
            remote.tcpNoDelay = true; remote.soTimeout = 10_000
            remote.connect(InetSocketAddress(socksHost, socksPort), 10_000)
            val input = DataInputStream(remote.getInputStream()); val output = remote.getOutputStream()
            Socks5.authenticateClient(input, output, password)
            Socks5.request(input, output, 1, Socks5.Address(InetAddress.getByAddress(key.destinationBytes).hostAddress!!, key.destinationPort))
            onConnected() // SOCKS CONNECT succeeded; distinct from VPN flow creation
            remote.soTimeout = 0
            Thread({ writer(remote) }, "tun-tcp-writer").apply { isDaemon = true; start() }
            val buffer = ByteArray(transmitMss)
            while (!closed) {
                val available = synchronized(lock) {
                    while (!closed && (!established || distance(acknowledged, next) >= peerWindow)) lock.wait(1000)
                    if (closed) 0 else minOf(transmitMss.toLong(), peerWindow - distance(acknowledged, next)).toInt()
                }
                if (available <= 0) break
                val count = input.read(buffer, 0, available)
                if (count > 0) synchronized(lock) { lastActivity = System.nanoTime() }
                if (count < 0) { synchronized(lock) { if (!closed && !remoteFin) { remoteFin = true; sendTracked(0x11, ByteArray(0)) } }; break }
                synchronized(lock) {
                    // A peer can shrink its window while read() is blocked. Keep this one chunk bounded.
                    while (!closed && distance(acknowledged, next) + count > peerWindow) lock.wait(1000)
                    if (!closed) { sendTracked(0x18, buffer.copyOf(count)); onRx(count) }
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                onError(classifyUpstreamError(e))
                abort()
            }
        }
    }

    private fun classifyUpstreamError(e: Exception): String {
        val detail = e.message.orEmpty()
        // No destination host, user credentials or packet bytes appear in diagnostics.
        return when {
            detail.contains("Mobile data", true) -> "Sender mobile-data route unavailable"
            detail.contains("SOCKS error 3", true) -> "Sender network unreachable (SOCKS 3)"
            detail.contains("SOCKS error 4", true) -> "Sender destination unreachable (SOCKS 4)"
            detail.contains("SOCKS error 5", true) -> "Sender upstream connection refused/unavailable (SOCKS 5)"
            detail.contains("password", true) || detail.contains("authentication", true) -> "Sender proxy authentication failed"
            detail.contains("protect", true) || detail.contains("local link", true) -> "Receiver local Wi-Fi/VPN socket error"
            e is java.net.SocketTimeoutException -> "Sender proxy connection timed out"
            else -> "TCP upstream failed (${e.javaClass.simpleName})"
        }
    }

    private fun writer(remote: Socket) {
        try {
            val output = remote.getOutputStream()
            while (!closed) {
                val item = synchronized(lock) {
                    while (!closed && writes.isEmpty()) lock.wait(1000)
                    if (closed) null else writes.removeFirst()
                } ?: break
                if (item.fin) {
                    remote.shutdownOutput()
                    synchronized(lock) { outputFinished = true; if (finAcknowledged) close() }
                    return
                }
                val bytes = item.payload ?: continue
                output.write(bytes)
                synchronized(lock) {
                    lastActivity = System.nanoTime()
                    queuedBytes -= bytes.size
                    sendAck()
                    lock.notifyAll()
                }
            }
        } catch (e: Exception) { if (!closed) { onError("TCP upload failed (${e.javaClass.simpleName})"); abort() } }
    }

    /** Called by the single bridge maintenance thread, including when the upstream reader blocks. */
    fun tick(now: Long = System.nanoTime()) = synchronized(lock) {
        if (closed) return@synchronized
        val inactiveNanos = if (established) now - lastActivity else now - createdAt
        val timeoutNanos = if (established) TcpCapacityPolicy.IDLE_TIMEOUT_NANOS
            else TcpCapacityPolicy.HALF_OPEN_TIMEOUT_NANOS
        if (inactiveNanos > timeoutNanos) {
            onError(if (established) "TCP flow idle timeout" else "TCP handshake timed out")
            abort()
            return@synchronized
        }
        val oldest = pending.firstOrNull()
        if (oldest != null && now - oldest.sent >= minOf(4_000_000_000L, 500_000_000L shl minOf(oldest.attempts, 3))) {
            if (++oldest.attempts > 8) { onError("TCP peer did not acknowledge retransmissions"); abort(); return@synchronized }
            oldest.sent = now; onRetransmit(); emit(oldest)
        }
        if (peerWindow == 0 && now - lastProbe > 1_000_000_000L) {
            lastProbe = now
            writePacket(IpPackets.tcp(key, ((next - 1) and MASK).toInt(), expected.toInt(), 0x10, advertisedWindow(), ByteArray(0)))
        }
    }
    private fun sendTracked(flags: Int, payload: ByteArray) {
        if (closed) return
        val segment = Segment(next, flags, payload, System.nanoTime())
        pending.add(segment); next = (next + segment.length) and MASK; emit(segment)
    }
    private fun emit(segment: Segment) {
        writePacket(IpPackets.tcp(key, segment.sequence.toInt(), expected.toInt(),
            segment.flags, advertisedWindow(segment.flags and 0x02 != 0), segment.payload,
            if (segment.flags and 0x02 != 0)
                if (peerScale != null) SYN_SCALED_OPTION else SYN_MSS_OPTION
            else ByteArray(0)))
    }
    private fun sendAck() { if (!closed) writePacket(IpPackets.tcp(key, next.toInt(), expected.toInt(), 0x10, advertisedWindow(), ByteArray(0))) }
    fun abort() = synchronized(lock) {
        if (!closed) writePacket(IpPackets.tcp(key, next.toInt(), expected.toInt(), 0x14, 0, ByteArray(0)))
        close()
    }
    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true; try { socket?.close() } catch (_: IOException) {}
        writes.clear(); pending.clear(); lock.notifyAll(); onClose()
    }
}

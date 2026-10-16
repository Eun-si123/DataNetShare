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
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private const val CAPACITY = 65_535
        private const val MSS = 1200
        // RFC 793/879: explicitly advertise the segment size that our TUN bridge
        // can handle without fragmentation, instead of relying on 536-byte fallback.
        private val SYN_MSS_OPTION = byteArrayOf(2, 4, (MSS ushr 8).toByte(), MSS.toByte())
        private const val MASK = 0xffffffffL
        private fun unsigned(value: Int) = value.toLong() and MASK
        private fun distance(from: Long, to: Long) = (to - from) and MASK
    }
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
    private var peerWindow = CAPACITY
    private var appFin = false
    private var outputFinished = false
    private var remoteFin = false
    private var finAcknowledged = false
    private var established = false
    private var lastActivity = System.nanoTime()
    private var lastProbe = 0L
    @Volatile private var closed = false
    private var socket: Socket? = null

    fun start() = synchronized(lock) { if (!closed) sendTracked(0x12, ByteArray(0)) }
    fun retransmitSyn() = synchronized(lock) { pending.firstOrNull { it.flags and 2 != 0 }?.let(::emit) }

    fun receive(packet: Ipv4Packet) = synchronized(lock) {
        if (closed) return@synchronized
        lastActivity = System.nanoTime()
        if (packet.flags and 0x10 != 0) {
            val ack = unsigned(packet.acknowledgement)
            if (distance(acknowledged, ack) <= distance(acknowledged, next)) {
                acknowledged = ack; peerWindow = packet.window
                while (pending.isNotEmpty()) {
                    val segment = pending.first()
                    if (distance(segment.sequence, ack) < segment.length || distance(segment.sequence, ack) >= 0x80000000L) break
                    pending.removeFirst()
                    if (segment.flags and 2 != 0) established = true
                    if (segment.flags and 1 != 0) finAcknowledged = true
                }
                lock.notifyAll()
            }
        }
        if (established && !appFin) {
            val sequence = unsigned(packet.sequence)
            val overlap = distance(sequence, expected)
            val data = if (overlap <= packet.payload.size) packet.payload.copyOfRange(overlap.toInt(), packet.payload.size) else null
            if (data != null && data.isNotEmpty() && queuedBytes + data.size <= CAPACITY) {
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
        synchronized(lock) { if (closed) { remote.close(); return }; socket = remote }
        try {
            if (!protect(remote)) throw IOException("Could not protect proxy socket")
            remote.tcpNoDelay = true; remote.soTimeout = 10_000
            remote.connect(InetSocketAddress(socksHost, socksPort), 10_000)
            val input = DataInputStream(remote.getInputStream()); val output = remote.getOutputStream()
            Socks5.authenticateClient(input, output, password)
            Socks5.request(input, output, 1, Socks5.Address(InetAddress.getByAddress(key.destinationBytes).hostAddress!!, key.destinationPort))
            remote.soTimeout = 0
            Thread({ writer(remote) }, "tun-tcp-writer").apply { isDaemon = true; start() }
            val buffer = ByteArray(MSS)
            while (!closed) {
                val available = synchronized(lock) {
                    while (!closed && (!established || distance(acknowledged, next) >= peerWindow)) lock.wait(1000)
                    if (closed) 0 else minOf(MSS.toLong(), peerWindow - distance(acknowledged, next)).toInt()
                }
                if (available <= 0) break
                val count = input.read(buffer, 0, available)
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
                synchronized(lock) { queuedBytes -= bytes.size; sendAck(); lock.notifyAll() }
            }
        } catch (e: Exception) { if (!closed) { onError("TCP upload failed (${e.javaClass.simpleName})"); abort() } }
    }

    /** Called by the single bridge maintenance thread, including when the upstream reader blocks. */
    fun tick(now: Long = System.nanoTime()) = synchronized(lock) {
        if (closed) return@synchronized
        if (now - lastActivity > 120_000_000_000L) { onError("TCP flow idle timeout"); abort(); return@synchronized }
        val oldest = pending.firstOrNull()
        if (oldest != null && now - oldest.sent >= minOf(4_000_000_000L, 500_000_000L shl minOf(oldest.attempts, 3))) {
            if (++oldest.attempts > 8) { onError("TCP peer did not acknowledge retransmissions"); abort(); return@synchronized }
            oldest.sent = now; emit(oldest)
        }
        if (peerWindow == 0 && now - lastProbe > 1_000_000_000L) {
            lastProbe = now
            writePacket(IpPackets.tcp(key, ((next - 1) and MASK).toInt(), expected.toInt(), 0x10, CAPACITY - queuedBytes, ByteArray(0)))
        }
    }
    private fun sendTracked(flags: Int, payload: ByteArray) {
        if (closed) return
        val segment = Segment(next, flags, payload, System.nanoTime())
        pending.add(segment); next = (next + segment.length) and MASK; emit(segment)
    }
    private fun emit(segment: Segment) {
        writePacket(IpPackets.tcp(key, segment.sequence.toInt(), expected.toInt(),
            segment.flags, CAPACITY - queuedBytes, segment.payload,
            if (segment.flags and 0x02 != 0) SYN_MSS_OPTION else ByteArray(0)))
    }
    private fun sendAck() { if (!closed) writePacket(IpPackets.tcp(key, next.toInt(), expected.toInt(), 0x10, CAPACITY - queuedBytes, ByteArray(0))) }
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

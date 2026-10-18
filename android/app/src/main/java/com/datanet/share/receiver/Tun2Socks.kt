package com.datanet.share.receiver

import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructPollfd
import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import java.io.FileDescriptor
import java.io.FileInputStream
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Receiver metrics count attempts separately from successful SOCKS sessions. */
data class BridgeStats(
    val activeTcp: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val tcpAttempts: Long,
    val tcpConnected: Long,
    val udpAttempts: Long,
    val udpConnected: Long,
    val tunDropped: Long,
    val tcpRetransmits: Long,
    val tunWriteWaits: Long,
    val tunWriteWaitMs: Long,
    val activeUdp: Int,
    val idleTcpReclaimed: Long = 0,
    val udpQueueOverflows: Long = 0,
    val negotiatedTcpWindowScale: Long = 0,
    val ignoredIpv4Icmp: Long = 0,
    val ignoredIpv6Packets: Long = 0,
    val oversizedUdpReplies: Long = 0
)

class Tun2Socks(
    private val tunFd: FileDescriptor,
    private val socksHost: String,
    private val socksPort: Int,
    private val password: String,
    private val protect: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val onFailure: (String) -> Unit,
    private val onStats: (BridgeStats) -> Unit,
    private val onTcpError: (String) -> Unit = {},
    private val onUdpError: (String) -> Unit = {}
) {
    @Volatile private var running = false
    private val tcp = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val udp = ConcurrentHashMap<FlowKey, UdpFlow>()
    private val rx = AtomicLong(); private val tx = AtomicLong()
    private val tcpAttempts = AtomicLong(); private val tcpConnected = AtomicLong()
    private val udpAttempts = AtomicLong(); private val udpConnected = AtomicLong()
    private val tunWriteDrops = AtomicLong()
    private val tcpRetransmits = AtomicLong()
    private val idleTcpReclaimed = AtomicLong()
    private val udpQueueOverflows = AtomicLong()
    private val windowScaledFlows = AtomicLong()
    private val ipv4IcmpIgnored = AtomicLong()
    private val ipv6Ignored = AtomicLong()
    private val oversizedUdpReplies = AtomicLong()
    private val tunWriteWaits = AtomicLong()
    private val tunWriteWaitNanos = AtomicLong()
    private val reportedDropBatch = AtomicLong(-1)
    private val writeLock = Any()
    // The joint admission budget reserves eight sender SOCKS5 clients for probes.
    // With few UDP associations, TCP may borrow unused capacity up to 112.

    fun start() {
        check(!running); running = true
        Thread({ readLoop() }, "tun-read").apply { isDaemon = true; start() }
        Thread({
            while (running) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                tcp.values.forEach { it.tick() }
                if (running) {
                    onStats(BridgeStats(tcp.size, rx.get(), tx.get(),
                        tcpAttempts.get(), tcpConnected.get(), udpAttempts.get(),
                        udpConnected.get(), tunWriteDrops.get(), tcpRetransmits.get(),
                        tunWriteWaits.get(), tunWriteWaitNanos.get() / 1_000_000L, udp.size,
                        idleTcpReclaimed.get(), udpQueueOverflows.get(),
                        windowScaledFlows.get(), ipv4IcmpIgnored.get(), ipv6Ignored.get(),
                        oversizedUdpReplies.get()))
                    // Non-sensitive congestion telemetry; avoid printing destination addresses.
                    val dropped = tunWriteDrops.get()
                    val batch = dropped / 50L
                    if ((dropped == 1L || batch > 0) && batch != reportedDropBatch.getAndSet(batch)) {
                        onTcpError("VPN write congestion: $dropped dropped TUN packets")
                    }
                }
            }
        }, "tun-maintenance").apply { isDaemon = true; start() }
    }
    fun stop() {
        running = false
        // Closing a flow removes it from its ConcurrentHashMap via onClose.
        // Kotlin toList() can race with concurrent removals and throw, leaving
        // the VPN cleanup incomplete. CHM iteration is weakly consistent.
        tcp.values.forEach { it.close() }; tcp.clear()
        udp.values.forEach { it.close() }; udp.clear()
        // ReceiverVpnService owns/closes the descriptor. readLoop polls with a finite timeout.
    }
    private fun readLoop() {
        try {
            FileInputStream(Os.dup(tunFd)).use { input ->
                val buffer = ByteArray(65535)
                val poll = StructPollfd().apply { fd = input.fd; events = OsConstants.POLLIN.toShort() }
                while (running) {
                    if (Os.poll(arrayOf(poll), 500) == 0) continue
                    if (!running) break
                    if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) error("VPN interface disconnected")
                    if (poll.revents.toInt() and OsConstants.POLLIN == 0) continue
                    val count = input.read(buffer)
                    if (count < 0) error("VPN interface ended")
                    // The current bridge only decodes IPv4 TCP/UDP.
                    // Count discarded ICMP/IPv6 packets for Speedtest/app
                    // compatibility diagnosis without recording destinations.
                    if (count > 0) {
                        val version = (buffer[0].toInt() and 255) ushr 4
                        if (version == 6) {
                            ipv6Ignored.incrementAndGet()
                            continue
                        }
                        if (version == 4 && count >= 20 && (buffer[9].toInt() and 255) == 1) {
                            ipv4IcmpIgnored.incrementAndGet()
                            continue
                        }
                    }
                    val packet = Ipv4Packet.parse(buffer, count) ?: continue
                    if (packet.protocol == 6) handleTcp(packet) else handleUdp(packet)
                }
            }
        } catch (e: Exception) { if (running) { stop(); onFailure("VPN packet loop stopped: ${e.message}") } }
    }
    private fun handleTcp(packet: Ipv4Packet) {
        val key = packet.key
        if (packet.flags and 4 != 0) { tcp.remove(key)?.close(); return }
        if (packet.flags and 2 != 0) {
            tcp[key]?.let { it.retransmitSyn(); return }
            if (!TcpCapacityPolicy.allowTcp(tcp.size, udp.size)) {
                // Reclaim only genuinely idle established flows or abandoned
                // handshakes; never kill a flow with unacknowledged TCP data.
                val now = System.nanoTime()
                tcp.values.forEach { flow ->
                    if (!TcpCapacityPolicy.allowTcp(tcp.size, udp.size) &&
                        flow.reclaimableForCapacity(now)) {
                        flow.close()
                        idleTcpReclaimed.incrementAndGet()
                    }
                }
                if (!TcpCapacityPolicy.allowTcp(tcp.size, udp.size)) {
                    onTcpError("Receiver TCP capacity reached (active ${tcp.size}, UDP ${udp.size}, total budget ${TcpCapacityPolicy.TOTAL_BUDGET})")
                    return
                }
            }
            lateinit var flow: TcpFlow
            flow = TcpFlow(key, packet.sequence, socksHost, socksPort, password, protect, ::writePacket,
                { rx.addAndGet(it.toLong()) }, { tx.addAndGet(it.toLong()) }, { tcp.remove(key, flow) },
                onError = onTcpError,
                onConnected = {
                    tcpConnected.incrementAndGet()
                    if (packet.offeredWindowScale() != null) windowScaledFlows.incrementAndGet()
                },
                onRetransmit = { tcpRetransmits.incrementAndGet() },
                peerMss = packet.offeredMss() ?: 536,
                peerWindowScale = packet.offeredWindowScale())
            tcp[key] = flow
            tcpAttempts.incrementAndGet()
            flow.start()
            Thread({ flow.openUpstream() }, "tun-tcp-connect").apply { isDaemon = true; start() }
        } else tcp[key]?.receive(packet)
    }
    private fun handleUdp(packet: Ipv4Packet) {
        val key = packet.key
        val existing = udp[key]
        if (existing != null) { existing.send(packet.payload); return }
        if (!TcpCapacityPolicy.allowUdp(tcp.size, udp.size)) {
            // Free a genuinely idle QUIC/DNS session instead of rejecting every
            // new flow until the background 30s idle timer eventually expires.
            val now = System.nanoTime()
            udp.values.forEach { flow ->
                if (!TcpCapacityPolicy.allowUdp(tcp.size, udp.size) &&
                    flow.idleForAtLeast(now, UdpFlowPolicy.EVICT_IDLE_NANOS)) flow.close()
            }
            if (!TcpCapacityPolicy.allowUdp(tcp.size, udp.size)) {
                onUdpError("Receiver UDP capacity reached (TCP ${tcp.size}, UDP ${udp.size}, total budget ${TcpCapacityPolicy.TOTAL_BUDGET})")
                return
            }
        }
        lateinit var flow: UdpFlow
        flow = UdpFlow(key, socksHost, socksPort, password, protect, protectDatagram, ::writePacket,
            { rx.addAndGet(it.toLong()) }, { tx.addAndGet(it.toLong()) }, { udp.remove(key, flow) },
            onError = { message ->
                if (message.contains("queue full")) udpQueueOverflows.incrementAndGet()
                onUdpError(message)
            },
            onConnected = { udpConnected.incrementAndGet() },
            onOversizedReply = { oversizedUdpReplies.incrementAndGet() })
        udp[key] = flow; udpAttempts.incrementAndGet(); flow.send(packet.payload)
        Thread({ flow.start() }, "tun-udp-connect").apply { isDaemon = true; start() }
    }
    private fun writePacket(packet: ByteArray) = synchronized(writeLock) {
        if (!running) return@synchronized
        try {
            val delivered = TunWriteRetry.attempt(
                write = {
                    try {
                        val written = Os.write(tunFd, ByteBuffer.wrap(packet))
                        when (written) {
                            packet.size -> true
                            0 -> false
                            else -> error("Incomplete VPN packet write: $written / ${packet.size}")
                        }
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) false
                        else throw e
                    }
                },
                awaitWritable = {
                    val waiting = StructPollfd().apply { fd = tunFd; events = OsConstants.POLLOUT.toShort() }
                    val started = System.nanoTime()
                    try {
                        Os.poll(arrayOf(waiting), 20) > 0 &&
                            waiting.revents.toInt() and OsConstants.POLLOUT != 0
                    } finally {
                        tunWriteWaits.incrementAndGet()
                        tunWriteWaitNanos.addAndGet((System.nanoTime() - started).coerceAtLeast(0L))
                    }
                }
            )
            // When the queue stays full, only this datagram is dropped. TCP
            // retransmission can recover; never block the single TUN reader forever.
            if (!delivered) tunWriteDrops.incrementAndGet()
        } catch (e: Exception) {
            if (running) { running = false; onFailure("VPN packet write failed: ${e.message}") }
        }
    }
}

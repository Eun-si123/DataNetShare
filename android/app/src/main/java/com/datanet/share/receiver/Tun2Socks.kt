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

class Tun2Socks(
    private val tunFd: FileDescriptor,
    private val socksHost: String,
    private val socksPort: Int,
    private val password: String,
    private val protect: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val onFailure: (String) -> Unit,
    private val onStats: (Int, Long, Long) -> Unit,
    private val onTcpError: (String) -> Unit = {}
) {
    @Volatile private var running = false
    private val tcp = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val udp = ConcurrentHashMap<FlowKey, UdpFlow>()
    private val rx = AtomicLong(); private val tx = AtomicLong()
    private val writeLock = Any()

    fun start() {
        check(!running); running = true
        Thread({ readLoop() }, "tun-read").apply { isDaemon = true; start() }
        Thread({
            while (running) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                tcp.values.forEach { it.tick() }
                if (running) onStats(tcp.size, rx.get(), tx.get())
            }
        }, "tun-maintenance").apply { isDaemon = true; start() }
    }
    fun stop() {
        running = false
        tcp.values.toList().forEach { it.close() }; tcp.clear()
        udp.values.toList().forEach { it.close() }; udp.clear()
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
            if (tcp.size >= 64) return
            lateinit var flow: TcpFlow
            flow = TcpFlow(key, packet.sequence, socksHost, socksPort, password, protect, ::writePacket,
                { rx.addAndGet(it.toLong()) }, { tx.addAndGet(it.toLong()) }, { tcp.remove(key, flow) },
                onError = onTcpError)
            tcp[key] = flow; flow.start()
            Thread({ flow.openUpstream() }, "tun-tcp-connect").apply { isDaemon = true; start() }
        } else tcp[key]?.receive(packet)
    }
    private fun handleUdp(packet: Ipv4Packet) {
        val key = packet.key
        val existing = udp[key]
        if (existing != null) { existing.send(packet.payload); return }
        if (udp.size >= 32) return
        lateinit var flow: UdpFlow
        flow = UdpFlow(key, socksHost, socksPort, password, protect, protectDatagram, ::writePacket,
            { rx.addAndGet(it.toLong()) }, { tx.addAndGet(it.toLong()) }, { udp.remove(key, flow) })
        udp[key] = flow; flow.send(packet.payload)
        Thread({ flow.start() }, "tun-udp-connect").apply { isDaemon = true; start() }
    }
    private fun writePacket(packet: ByteArray) = synchronized(writeLock) {
        if (!running) return@synchronized
        try {
            val written = Os.write(tunFd, ByteBuffer.wrap(packet))
            check(written == packet.size) { "Incomplete VPN packet write" }
        } catch (e: ErrnoException) {
            // A full nonblocking TUN queue is packet loss, not a dead interface.
            // TCP keeps unacknowledged packets for retransmission; UDP is best effort.
            if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR && running) {
                running = false; onFailure("VPN packet write failed: ${e.message}")
            }
        } catch (e: Exception) { if (running) { running = false; onFailure("VPN packet write failed: ${e.message}") } }
    }
}

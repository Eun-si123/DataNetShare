package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.IpPackets
import com.datanet.share.common.Socks5
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Separate SOCKS association per original UDP flow, including its first DNS packet. */
class UdpFlow(
    private val key: FlowKey,
    private val host: String,
    private val port: Int,
    private val password: String,
    private val protect: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val writePacket: (ByteArray) -> Unit,
    private val onRx: (Int) -> Unit,
    private val onTx: (Int) -> Unit,
    private val onClose: () -> Unit
) {
    private val closed = AtomicBoolean()
    private val queue = ArrayBlockingQueue<ByteArray>(32)
    private var control: Socket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var lastActivity = System.nanoTime()
    fun send(payload: ByteArray) { if (!closed.get()) { queue.offer(payload); lastActivity = System.nanoTime() } }

    fun start() {
        try {
            val socket = Socket()
            synchronized(this) { if (closed.get()) { socket.close(); return }; control = socket }
            if (!protect(socket)) throw IOException("UDP control socket protection failed")
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress(host, port), 10_000)
            val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
            Socks5.authenticateClient(input, output, password)
            val bound = Socks5.request(input, output, 3, Socks5.Address("0.0.0.0", 0))
            if (bound.port == 0) throw IOException("Invalid UDP relay port")
            val relayHost = if (bound.host == "0.0.0.0" || bound.host == "0:0:0:0:0:0:0:0") host else bound.host
            val datagram = DatagramSocket(null)
            synchronized(this) { if (closed.get()) { datagram.close(); return }; udp = datagram }
            if (!protectDatagram(datagram)) throw IOException("UDP relay socket protection failed")
            datagram.connect(InetSocketAddress(relayHost, bound.port)); datagram.soTimeout = 1000
            socket.soTimeout = 1000
            Thread({
                try {
                    while (!closed.get()) {
                        try { if (input.read() < 0) break } catch (_: SocketTimeoutException) {}
                        if (System.nanoTime() - lastActivity > 120_000_000_000L) break
                    }
                } catch (_: IOException) {} finally { close() }
            }, "tun-udp-control").apply { isDaemon = true; start() }
            Thread({ receive(datagram) }, "tun-udp-receive").apply { isDaemon = true; start() }
            val destination = Socks5.Address(InetAddress.getByAddress(key.destinationBytes).hostAddress!!, key.destinationPort)
            while (!closed.get()) {
                val payload = queue.poll(1, TimeUnit.SECONDS) ?: continue
                val bytes = Socks5.encodeDatagram(destination, payload)
                datagram.send(DatagramPacket(bytes, bytes.size)); onTx(payload.size)
            }
        } catch (_: Exception) {} finally { close() }
    }
    private fun receive(socket: DatagramSocket) {
        val buffer = ByteArray(65535)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val decoded = Socks5.decodeDatagram(packet.data.copyOfRange(packet.offset, packet.offset + packet.length)) ?: continue
                if (decoded.address.port != key.destinationPort ||
                    !InetAddress.getByName(decoded.address.host).address.contentEquals(key.destinationBytes)) continue
                // Oversize datagrams require IP fragmentation, which this IPv4 receiver does not implement.
                if (decoded.payload.size > 1472) continue
                writePacket(IpPackets.udp(key, decoded.payload)); onRx(decoded.payload.size)
                lastActivity = System.nanoTime()
            }
        } catch (_: IOException) {} finally { close() }
    }
    @Synchronized fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { control?.close() } catch (_: IOException) {}; udp?.close(); queue.clear(); onClose()
    }
}

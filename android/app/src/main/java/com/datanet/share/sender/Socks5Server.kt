package com.datanet.share.sender

import com.datanet.share.common.Socks5
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/** Bounded SOCKS5 relay. No Android dependencies: real sockets are exercised by JVM tests. */
class Socks5Server(
    private val bindHost: String,
    private val port: Int,
    private val authPassword: String,
    private val upstream: Upstream,
    private val onStats: (clients: Int, rxBytes: Long, txBytes: Long) -> Unit = { _, _, _ -> },
    private val onFailure: (String) -> Unit = {},
    private val onUpstreamFailure: (String) -> Unit = {},
    private val onClientLimit: () -> Unit = {}
) {
    interface Upstream {
        fun connect(host: String, port: Int): Socket
        fun resolve(host: String): Array<InetAddress>
        fun datagram(): DatagramSocket
    }
    private val permits = Semaphore(64)
    private val datagramPermits = Semaphore(128)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val associations = ConcurrentHashMap.newKeySet<UdpAssociate>()
    private val rx = AtomicLong()
    private val tx = AtomicLong()
    @Volatile private var running = false
    private var listener: ServerSocket? = null
    val boundPort: Int get() = listener?.localPort ?: 0
    val clientCount: Int get() = clients.size

    @Synchronized fun start() {
        check(!running) { "Proxy is already running" }
        require(authPassword.toByteArray(Charsets.UTF_8).size in 1..255)
        val server = ServerSocket()
        try { server.reuseAddress = true; server.bind(InetSocketAddress(bindHost, port), 64) }
        catch (e: Exception) { server.close(); throw e }
        listener = server; running = true
        Thread({
            try {
                while (running) {
                    val client = server.accept()
                    if (!running) { client.close(); continue }
                    if (!permits.tryAcquire()) { onClientLimit(); client.close(); continue }
                    clients.add(client); sockets.add(client)
                    Thread({
                        try { if (running) handleClient(client) }
                        finally {
                            clients.remove(client); sockets.remove(client); client.close(); permits.release()
                            onStats(clients.size, rx.get(), tx.get())
                        }
                    }, "socks-client").apply { isDaemon = true; start() }
                }
            } catch (e: IOException) { if (running) { stop(); onFailure("Proxy listener stopped: ${e.message}") } }
        }, "socks-accept").apply { isDaemon = true; start() }
        Thread({
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (running) onStats(clients.size, rx.get(), tx.get())
            }
        }, "socks-stats").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        running = false
        try { listener?.close() } catch (_: IOException) {}
        listener = null
        associations.toList().forEach { it.close() }
        sockets.toList().forEach { try { it.close() } catch (_: IOException) {} }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true; client.soTimeout = 15_000
            val input = DataInputStream(client.getInputStream()); val output = client.getOutputStream()
            Socks5.authenticateServer(input, output, authPassword)
            if (input.readUnsignedByte() != 5) throw IOException("Invalid SOCKS request")
            val command = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) throw IOException("Invalid SOCKS reserved byte")
            val address = Socks5.readAddress(input)
            when (command) {
                1 -> {
                    val remote = try { upstream.connect(address.host, address.port) }
                    catch (e: IOException) {
                        val missingCellular = e.message?.contains("Mobile data is unavailable", true) == true
                        val replyCode = when {
                            missingCellular -> 3 // RFC 1928: network unreachable
                            e is java.net.UnknownHostException -> 4 // host unreachable
                            else -> 5 // connection refused/unavailable
                        }
                        // Do not expose destination names, IPs or session secrets in UI state.
                        onUpstreamFailure(when (replyCode) {
                            3 -> "Mobile data unavailable; enable data or select Android default route"
                            4 -> "DNS/remote host unreachable on selected upstream network"
                            else -> "Remote TCP connect failed (${e.javaClass.simpleName})"
                        })
                        Socks5.reply(output, replyCode)
                        return
                    }
                    sockets.add(remote)
                    try {
                        if (!running) return
                        client.soTimeout = 0; remote.soTimeout = 0; remote.tcpNoDelay = true
                        Socks5.reply(output, 0)
                        // EOF in one direction only closes that output half; the peer can still reply.
                        val upload = Thread({ relay(client, remote, rx) }, "socks-upload").apply { isDaemon = true; start() }
                        relay(remote, client, tx)
                        upload.join()
                    } finally { sockets.remove(remote); remote.close() }
                }
                3 -> {
                    client.soTimeout = 1000
                    val assoc = UdpAssociate(client)
                    associations.add(assoc)
                    try {
                        assoc.start()
                        Socks5.reply(output, 0, Socks5.Address(client.localAddress.hostAddress!!, assoc.port))
                        while (running && !assoc.closed) {
                            try { if (input.read() == -1) break } catch (_: SocketTimeoutException) {}
                            if (System.nanoTime() - assoc.lastActivity.get() > 120_000_000_000L) break
                        }
                    } finally { assoc.close(); associations.remove(assoc) }
                }
                else -> Socks5.reply(output, 7)
            }
        } catch (_: IOException) { /* A bad handshake only terminates this client. */ }
    }

    private fun relay(from: Socket, to: Socket, counter: AtomicLong) {
        try {
            val input = from.getInputStream(); val output = to.getOutputStream(); val buffer = ByteArray(32 * 1024)
            while (running) {
                val count = input.read(buffer)
                if (count < 0) { to.shutdownOutput(); return }
                if (count > 0) { output.write(buffer, 0, count); counter.addAndGet(count.toLong()) }
            }
        } catch (_: IOException) { try { from.close() } catch (_: IOException) {}; try { to.close() } catch (_: IOException) {} }
    }

    private inner class UdpAssociate(private val client: Socket) {
        private val relay = DatagramSocket(InetSocketAddress(client.localAddress, 0))
        private val remoteSockets = ConcurrentHashMap<InetSocketAddress, DatagramSocket>()
        @Volatile var closed = false
        private var clientEndpoint: InetSocketAddress? = null
        val port: Int get() = relay.localPort
        val lastActivity = AtomicLong(System.nanoTime())

        fun start() {
            Thread({
                val buffer = ByteArray(65535)
                while (running && !closed) {
                    try {
                        // A fresh packet resets receive capacity after a short datagram.
                        val packet = DatagramPacket(buffer, buffer.size); relay.receive(packet)
                        val source = packet.socketAddress as InetSocketAddress
                        if (source.address != client.inetAddress) continue
                        if (clientEndpoint == null) clientEndpoint = source
                        if (source != clientEndpoint) continue
                        val data = Socks5.decodeDatagram(packet.data.copyOfRange(packet.offset, packet.offset + packet.length)) ?: continue
                        if (data.address.port == 0 || data.payload.size > 65_507) continue
                        val destination = InetSocketAddress(upstream.resolve(data.address.host).first(), data.address.port)
                        val remote = remoteSockets[destination] ?: createRemote(destination) ?: continue
                        lastActivity.set(System.nanoTime())
                        remote.send(DatagramPacket(data.payload, data.payload.size))
                        rx.addAndGet(data.payload.size.toLong())
                    } catch (_: SocketTimeoutException) { continue }
                    catch (_: IOException) { if (relay.isClosed) break }
                }
            }, "socks-udp-relay").apply { isDaemon = true; start() }
        }

        private fun createRemote(destination: InetSocketAddress): DatagramSocket? {
            // One endpoint per socket prevents unsolicited remote responses; per-association cap bounds FDs.
            if (remoteSockets.size >= 32 || closed || !running || !datagramPermits.tryAcquire()) return null
            var allocated: DatagramSocket? = null
            try {
                val socket = upstream.datagram(); allocated = socket
                socket.connect(destination); socket.soTimeout = 1000
                synchronized(this) {
                    if (closed || !running) { socket.close(); datagramPermits.release(); return null }
                    remoteSockets[destination] = socket
                }
                Thread({
                    val buffer = ByteArray(65535)
                    try {
                        while (running && !closed) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                            val endpoint = clientEndpoint ?: continue
                            val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            val response = Socks5.encodeDatagram(Socks5.Address(destination.address.hostAddress!!, destination.port), payload)
                            if (response.size > 65_507) continue
                            relay.send(DatagramPacket(response, response.size, endpoint))
                            tx.addAndGet(payload.size.toLong()); lastActivity.set(System.nanoTime())
                        }
                    } catch (_: IOException) { /* close() unblocks receive */ }
                    finally { remoteSockets.remove(destination, socket); socket.close(); datagramPermits.release() }
                }, "socks-udp-upstream").apply { isDaemon = true; start() }
                return socket
            } catch (e: Exception) { allocated?.close(); datagramPermits.release(); throw e }
        }

        @Synchronized fun close() {
            closed = true; relay.close(); remoteSockets.values.forEach { it.close() }; remoteSockets.clear()
        }
    }
}

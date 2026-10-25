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
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded SOCKS5 relay. No Android dependencies: real sockets are exercised by JVM tests. */
class Socks5Server(
    private val bindHost: String,
    private val port: Int,
    private val authPassword: String,
    private val upstream: Upstream,
    private val onStats: (clients: Int, rxBytes: Long, txBytes: Long) -> Unit = { _, _, _ -> },
    private val onFailure: (String) -> Unit = {},
    private val onUpstreamFailure: (String) -> Unit = {},
    private val onClientLimit: () -> Unit = {},
    private val onIdleClose: () -> Unit = {},
    private val maxClients: Int = 128,
    private val tcpIdleMillis: Long = 45_000,
    private val socketPollMillis: Int = 2_000,
    private val onPeers: (List<PeerUsageLedger.Snapshot>) -> Unit = {}
) {
    init {
        require(maxClients in 1..192) { "Sender client limit must be bounded" }
        require(tcpIdleMillis in 500..600_000) { "Invalid TCP idle timeout" }
        require(socketPollMillis in 100..10_000) { "Invalid socket polling interval" }
    }

    data class UdpConnection(val socket: DatagramSocket, val destination: InetSocketAddress)

    interface Upstream {
        fun connect(host: String, port: Int): Socket
        fun resolve(host: String): Array<InetAddress>
        fun datagram(): DatagramSocket

        /** Resolve and prepare the UDP socket as one operation.
         * Android overrides this to keep DNS and socket binding on the same Network.
         * This only retries before the first payload is sent.
         */
        fun openUdp(host: String, port: Int): UdpConnection {
            if (port !in 1..65535) throw IOException("Invalid UDP destination port")
            val address = resolve(host).firstOrNull() ?: throw IOException("No UDP destination address")
            val destination = InetSocketAddress(address, port)
            val socket = datagram()
            try {
                socket.connect(destination)
                return UdpConnection(socket, destination)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
    }
    private val permits = Semaphore(maxClients)
    private val datagramPermits = Semaphore(128)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val associations = ConcurrentHashMap.newKeySet<UdpAssociate>()
    private val rx = AtomicLong()
    private val tx = AtomicLong()
    private val usage = PeerUsageLedger()
    private val lastPeersSent = AtomicLong(0)
    @Volatile private var running = false
    @Volatile private var listener: ServerSocket? = null
    val boundPort: Int get() = listener?.localPort ?: 0
    val clientCount: Int get() = clients.size
    /** Read-only session snapshot; client IPs are local UI data, not diagnostics. */
    fun peerSnapshots(): List<PeerUsageLedger.Snapshot> = usage.snapshot()

    @Synchronized fun start() {
        check(!running) { "Proxy is already running" }
        require(authPassword.toByteArray(Charsets.UTF_8).size in 1..255)
        val server = ServerSocket()
        try { server.reuseAddress = true; server.bind(InetSocketAddress(bindHost, port), minOf(maxClients, 128)) }
        catch (e: Exception) { server.close(); throw e }
        usage.clear()
        listener = server; running = true
        Thread({
            try {
                while (running && listener === server) {
                    val client = server.accept()
                    if (!running || listener !== server) { client.close(); continue }
                    if (!permits.tryAcquire()) {
                        try {
                            onClientLimit()
                        } catch (_: Exception) {
                            // A nonessential reporting failure must not kill the accept loop.
                        } finally {
                            try { client.close() } catch (_: IOException) {}
                        }
                        continue
                    }
                    clients.add(client); sockets.add(client)
                    Thread({
                        var peer: PeerUsageLedger.Entry? = null
                        try {
                            if (running && listener === server) handleClient(client) {
                                usage.open(client.inetAddress.hostAddress).also { peer = it }
                            }
                        } finally {
                            usage.close(peer)
                            clients.remove(client); sockets.remove(client); client.close(); permits.release()
                            publishStats()
                        }
                    }, "socks-client").apply { isDaemon = true; start() }
                }
            } catch (e: IOException) {
                // A previous accept thread can outlive Stop and a quick restart.
                // Check listener identity *under the same lock* as Stop/Start so
                // an old closed ServerSocket cannot tear down the new session.
                var failedCurrentSession = false
                synchronized(this@Socks5Server) {
                    if (running && listener === server) {
                        stop()
                        failedCurrentSession = true
                    }
                }
                if (failedCurrentSession) onFailure(SenderFailureMessages.LISTENER_STOPPED)
            }
        }, "socks-accept").apply { isDaemon = true; start() }
        Thread({
            while (running && listener === server) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (running && listener === server) publishStats()
            }
        }, "socks-stats").apply { isDaemon = true; start() }
    }

    private fun publishStats() {
        // Reporting is optional: a UI/telemetry callback failure must not
        // terminate the periodic stats worker or an active client worker.
        try {
            onStats(clients.size, rx.get(), tx.get())
        } catch (_: Exception) {}
        // Socket closes can arrive in huge bursts during speed tests.
        // UI peer snapshots are no more frequent than once per second.
        val now = System.nanoTime()
        val previous = lastPeersSent.get()
        if (now - previous > 1_000_000_000L &&
            lastPeersSent.compareAndSet(previous, now)) {
            try {
                onPeers(usage.snapshot())
            } catch (_: Exception) {}
        }
    }

    @Synchronized fun stop() {
        running = false
        try { listener?.close() } catch (_: IOException) {}
        listener = null
        // ConcurrentHashMap-backed KeySetView is weakly consistent. Do NOT use
        // Kotlin toList(): it snapshots size then iterates, and a concurrent
        // remove can cause NoSuchElementException in the iterator-based copy.
        // Direct CHM traversal tolerates active relay cleanup during Stop.
        associations.forEach { it.close() }
        sockets.forEach { try { it.close() } catch (_: IOException) {} }
    }

    private fun handleClient(client: Socket, authenticated: () -> PeerUsageLedger.Entry?) {
        try {
            client.tcpNoDelay = true; client.soTimeout = 15_000
            val input = DataInputStream(client.getInputStream()); val output = client.getOutputStream()
            Socks5.authenticateServer(input, output, authPassword)
            val peer = authenticated()
            if (input.readUnsignedByte() != 5) throw IOException("Invalid SOCKS request")
            val command = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) throw IOException("Invalid SOCKS reserved byte")
            val addressType = input.readUnsignedByte()
            if (addressType != 1 && addressType != 3 && addressType != 4) {
                Socks5.reply(output, 8) // RFC 1928: address type not supported
                return
            }
            val address = try { Socks5.readAddress(input, addressType) }
            catch (_: IOException) {
                Socks5.reply(output, 1) // Malformed address: general SOCKS failure
                return
            }
            when (command) {
                1 -> {
                    // TCP CONNECT requires a usable destination port. Never hand
                    // port zero to the upstream (which may throw unchecked errors).
                    if (address.port == 0) {
                        Socks5.reply(output, 1)
                        return
                    }
                    val remote = try { upstream.connect(address.host, address.port) }
                    catch (e: IOException) {
                        val missingCellular = e.message?.contains("Mobile data is unavailable", true) == true
                        val replyCode = when {
                            missingCellular -> 3 // RFC 1928: network unreachable
                            e is java.net.UnknownHostException -> 4 // host unreachable
                            else -> 5 // connection refused/unavailable
                        }
                        // Do not expose destination names, IPs or session secrets in UI state.
                        try {
                            onUpstreamFailure(when (replyCode) {
                                3 -> "Mobile data unavailable; enable data or select Android default route"
                                4 -> "DNS/remote host unreachable on selected upstream network"
                                else -> "Remote TCP connect failed (${e.javaClass.simpleName})"
                            })
                        } catch (_: Exception) {
                            // Optional reporting must not suppress the SOCKS error reply.
                        }
                        Socks5.reply(output, replyCode)
                        return
                    }
                    sockets.add(remote)
                    try {
                        if (!running) return
                        // Read polls terminate abandoned browser keep-alives after a
                        // bounded idle window; streams that keep moving data are not reaped.
                        client.soTimeout = socketPollMillis
                        remote.soTimeout = socketPollMillis
                        remote.tcpNoDelay = true
                        Socks5.reply(output, 0)
                        val lastProgress = AtomicLong(System.nanoTime())
                        val idleReported = AtomicBoolean()
                        // EOF only half-closes a stream. Do not join forever when the
                        // opposite side stops responding without sending its own FIN.
                        val upload = Thread({
                            relay(client, remote, rx, lastProgress, idleReported, peer, uploading = true)
                        }, "socks-upload").apply { isDaemon = true; start() }
                        relay(remote, client, tx, lastProgress, idleReported, peer, uploading = false)
                        upload.join()
                    } finally { sockets.remove(remote); remote.close() }
                }
                3 -> {
                    client.soTimeout = 1000
                    val assoc = UdpAssociate(client, peer)
                    associations.add(assoc)
                    try {
                        assoc.start()
                        Socks5.reply(output, 0, Socks5.Address(client.localAddress.hostAddress!!, assoc.port))
                        while (running && !assoc.closed) {
                            try { if (input.read() == -1) break } catch (_: SocketTimeoutException) {}
                            if (System.nanoTime() - assoc.lastActivity.get() > 30_000_000_000L) break
                        }
                    } finally { assoc.close(); associations.remove(assoc) }
                }
                else -> Socks5.reply(output, 7)
            }
        } catch (_: IOException) { /* A bad handshake only terminates this client. */ }
    }

    private fun relay(from: Socket, to: Socket, counter: AtomicLong,
                      lastProgress: AtomicLong, idleReported: AtomicBoolean,
                      peer: PeerUsageLedger.Entry?, uploading: Boolean) {
        try {
            val input = from.getInputStream(); val output = to.getOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (running) {
                val count = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    // Both directions share a monotonic timestamp so an active
                    // download with a quiet upload channel is never considered idle.
                    val idle = System.nanoTime() - lastProgress.get()
                    if (idle >= tcpIdleMillis * 1_000_000L) {
                        if (idleReported.compareAndSet(false, true)) onIdleClose()
                        from.close(); to.close()
                        return
                    }
                    continue
                }
                if (count < 0) {
                    to.shutdownOutput()
                    return
                }
                if (count > 0) {
                    output.write(buffer, 0, count)
                    lastProgress.set(System.nanoTime())
                    counter.addAndGet(count.toLong())
                    if (uploading) peer?.received(count) else peer?.sent(count)
                }
            }
        } catch (_: IOException) {
            try { from.close() } catch (_: IOException) {}
            try { to.close() } catch (_: IOException) {}
        }
    }

    private inner class UdpAssociate(private val client: Socket,
                                     private val peer: PeerUsageLedger.Entry?) {
        private val relay = DatagramSocket(InetSocketAddress(client.localAddress, 0))
        private val remoteSockets = ConcurrentHashMap<Socks5.Address, DatagramSocket>()
        @Volatile var closed = false
        @Volatile private var clientEndpoint: InetSocketAddress? = null
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
                        // Malformed datagrams must not claim the authenticated client's UDP port.
                        // Only this relay thread writes the endpoint; upstream workers read it.
                        val pinned = clientEndpoint
                        if (pinned != null && source != pinned) continue
                        val data = Socks5.decodeDatagram(packet.data.copyOfRange(packet.offset, packet.offset + packet.length)) ?: continue
                        if (data.address.port == 0 || data.payload.size > 65_507) continue
                        if (pinned == null) clientEndpoint = source
                        // Keep the original host/port as the association key; resolution and
                        // socket binding happen together when a destination is first opened.
                        val target = data.address
                        val remote = try { remoteSockets[target] ?: createRemote(target) }
                        catch (_: RuntimeException) {
                            // Android network binding/permission failures can be unchecked.
                            // createRemote releases its permit and socket on failure;
                            // keep this authenticated UDP association alive for retry.
                            continue
                        } ?: continue
                        lastActivity.set(System.nanoTime())
                        try {
                            remote.send(DatagramPacket(data.payload, data.payload.size))
                        } catch (_: IOException) {
                            // A failed send may mean the bound network disappeared. Retire
                            // this socket so a *later* packet can select a usable network.
                            // Never replay this datagram: the remote may have received it.
                            remoteSockets.remove(target, remote)
                            remote.close() // also wakes the receive worker to release its permit
                            continue
                        }
                        rx.addAndGet(data.payload.size.toLong())
                        peer?.received(data.payload.size)
                    } catch (_: SocketTimeoutException) { continue }
                    catch (_: IOException) { if (relay.isClosed) break }
                }
            }, "socks-udp-relay").apply { isDaemon = true; start() }
        }

        private fun createRemote(target: Socks5.Address): DatagramSocket? {
            // One endpoint per socket prevents unsolicited remote responses; per-association cap bounds FDs.
            if (remoteSockets.size >= 32 || closed || !running || !datagramPermits.tryAcquire()) return null
            var allocated: DatagramSocket? = null
            try {
                val connection = upstream.openUdp(target.host, target.port)
                val socket = connection.socket; allocated = socket
                val destination = connection.destination
                socket.soTimeout = 1000
                synchronized(this) {
                    if (closed || !running) { socket.close(); datagramPermits.release(); return null }
                    remoteSockets[target] = socket
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
                            tx.addAndGet(payload.size.toLong())
                            peer?.sent(payload.size)
                            lastActivity.set(System.nanoTime())
                        }
                    } catch (_: IOException) { /* close() unblocks receive */ }
                    finally { remoteSockets.remove(target, socket); socket.close(); datagramPermits.release() }
                }, "socks-udp-upstream").apply { isDaemon = true; start() }
                return socket
            } catch (e: Exception) { allocated?.close(); datagramPermits.release(); throw e }
        }

        @Synchronized fun close() {
            closed = true; relay.close(); remoteSockets.values.forEach { it.close() }; remoteSockets.clear()
        }
    }
}

package com.datanet.share.sender

import com.datanet.share.common.Socks5
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

internal class ProxyFixture(
    private val failConnect: Boolean = false,
    private val noCellular: Boolean = false,
    maxClients: Int = 128,
    tcpIdleMillis: Long = 45_000,
    socketPollMillis: Int = 2_000
) : AutoCloseable {
    val password = "test-session-password"
    val idleClosures = AtomicInteger()
    val proxy = Socks5Server("127.0.0.1", 0, password, object : Socks5Server.Upstream {
        override fun connect(host: String, port: Int): Socket {
            if (noCellular) throw java.io.IOException("Mobile data is unavailable; no default-network fallback")
            if (failConnect) throw java.io.IOException("Simulated unavailable upstream")
            return Socket().apply { connect(InetSocketAddress(host, port), 1000) }
        }
        override fun resolve(host: String) = InetAddress.getAllByName(host)
        override fun datagram() = DatagramSocket()
    }, onIdleClose = { idleClosures.incrementAndGet() },
        maxClients = maxClients, tcpIdleMillis = tcpIdleMillis,
        socketPollMillis = socketPollMillis).also { it.start() }
    private val udp = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
    val udpPort get() = udp.localPort
    init {
        Thread({
            try {
                while (!udp.isClosed) {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    udp.receive(packet); udp.send(packet)
                }
            } catch (_: Exception) { }
        }, "test-udp-echo").apply { isDaemon = true; start() }
    }
    fun client() = Socket("127.0.0.1", proxy.boundPort).apply {
        soTimeout = 3000
        Socks5.authenticateClient(DataInputStream(getInputStream()), getOutputStream(), password)
    }
    fun associate(socket: Socket) = Socks5.request(DataInputStream(socket.getInputStream()), socket.getOutputStream(), 3, Socks5.Address("0.0.0.0", 0))
    override fun close() { proxy.stop(); udp.close() }
}

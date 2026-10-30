package com.datanet.share.sender

import com.datanet.share.common.Socks5
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Inject failures before Thread.start(), without exhausting CI's native threads. */
class Socks5WorkerLaunchFailureTest {
    private val loopback = "127.0.0.1"
    private val password = "test-session-password"

    private fun upstream(opened: AtomicReference<DatagramSocket>? = null) =
        object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket = error("unused")
            override fun resolve(host: String): Array<InetAddress> = arrayOf(InetAddress.getByName(loopback))
            override fun datagram(): DatagramSocket = DatagramSocket()
            override fun openUdp(host: String, port: Int): Socks5Server.UdpConnection {
                val destination = InetSocketAddress(InetAddress.getByName(loopback), port)
                val socket = DatagramSocket().apply { connect(destination) }
                opened?.set(socket)
                return Socks5Server.UdpConnection(socket, destination)
            }
        }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(description, condition())
    }

    private fun available(server: Socks5Server, name: String): Int {
        val field = Socks5Server::class.java.getDeclaredField(name)
        field.isAccessible = true
        return (field.get(server) as Semaphore).availablePermits()
    }

    @Test fun acceptWorkerOutOfMemoryClosesListenerBeforeReturningFailure() {
        val server = Socks5Server(loopback, 0, password, upstream(), workerLauncher = { worker ->
            if (worker.name == "socks-accept") throw OutOfMemoryError("synthetic pre-start failure")
            worker.start()
        })
        try {
            server.start()
            fail("Listener must not remain active")
        } catch (_: IOException) {
            assertEquals(0, server.boundPort)
            assertEquals(0, server.clientCount)
            assertEquals(128, available(server, "datagramPermits"))
        } finally { server.stop() }
    }

    @Test fun statsWorkerSecurityDenialClosesAlreadyStartedListener() {
        val server = Socks5Server(loopback, 0, password, upstream(), workerLauncher = { worker ->
            if (worker.name == "socks-stats") throw SecurityException("synthetic pre-start failure")
            worker.start()
        })
        try {
            server.start()
            fail("Partially started proxy must fail closed")
        } catch (_: IOException) {
            assertEquals(0, server.boundPort)
            assertEquals(0, server.clientCount)
        } finally { server.stop() }
    }

    @Test fun clientWorkerOutOfMemoryReturnsPermitAndStopsListenerOnce() {
        val reports = AtomicInteger()
        val server = Socks5Server(loopback, 0, password, upstream(), maxClients = 1,
            onFailure = { message ->
                assertEquals(SenderFailureMessages.LISTENER_STOPPED, message)
                reports.incrementAndGet()
            }, workerLauncher = { worker ->
                if (worker.name == "socks-client") throw OutOfMemoryError("synthetic pre-start failure")
                worker.start()
            })
        server.start()
        try {
            Socket(loopback, server.boundPort).use { client ->
                client.soTimeout = 1500
                await("listener must stop", { server.boundPort == 0 })
                await("client permit must return", { available(server, "permits") == 1 })
                assertEquals(0, server.clientCount)
                assertEquals(-1, client.getInputStream().read())
            }
            assertEquals(1, reports.get())
        } finally { server.stop() }
    }

    @Test fun tcpUploadSecurityDenialClosesUpstreamAndStopsListener() {
        ServerSocket(0, 1, InetAddress.getByName(loopback)).use { target ->
            val opened = AtomicReference<Socket>()
            val reports = AtomicInteger()
            val route = object : Socks5Server.Upstream {
                override fun connect(host: String, port: Int): Socket =
                    Socket(loopback, target.localPort).also { opened.set(it) }
                override fun resolve(host: String): Array<InetAddress> = arrayOf(InetAddress.getByName(loopback))
                override fun datagram(): DatagramSocket = DatagramSocket()
            }
            val server = Socks5Server(loopback, 0, password, route, maxClients = 1,
                onFailure = { reports.incrementAndGet() }, workerLauncher = { worker ->
                    if (worker.name == "socks-upload") throw SecurityException("synthetic pre-start failure")
                    worker.start()
                })
            server.start()
            try {
                Socket(loopback, server.boundPort).use { client ->
                    client.soTimeout = 1500
                    Socks5.authenticateClient(DataInputStream(client.getInputStream()),
                        client.getOutputStream(), password)
                    Socks5.request(DataInputStream(client.getInputStream()), client.getOutputStream(),
                        1, Socks5.Address(loopback, target.localPort))
                    await("TCP upstream must be created", { opened.get() != null })
                    await("TCP upstream must close", { opened.get().isClosed })
                    await("listener must stop", { server.boundPort == 0 })
                    await("client permit must return", { available(server, "permits") == 1 })
                    assertEquals(0, server.clientCount)
                }
                assertEquals(1, reports.get())
            } finally { server.stop() }
        }
    }

    @Test fun udpRelaySecurityDenialClosesControlAndStopsListener() {
        val reports = AtomicInteger()
        val server = Socks5Server(loopback, 0, password, upstream(), maxClients = 1,
            onFailure = { reports.incrementAndGet() }, workerLauncher = { worker ->
                if (worker.name == "socks-udp-relay") throw SecurityException("synthetic pre-start failure")
                worker.start()
            })
        server.start()
        try {
            Socket(loopback, server.boundPort).use { client ->
                client.soTimeout = 1500
                Socks5.authenticateClient(DataInputStream(client.getInputStream()),
                    client.getOutputStream(), password)
                try {
                    Socks5.request(DataInputStream(client.getInputStream()), client.getOutputStream(),
                        3, Socks5.Address("0.0.0.0", 0))
                } catch (_: IOException) { /* No success reply after launch failure. */ }
                await("UDP relay failure must stop listener", { server.boundPort == 0 })
                await("control permit must return", { available(server, "permits") == 1 })
                assertEquals(0, server.clientCount)
            }
            assertEquals(1, reports.get())
        } finally { server.stop() }
    }

    @Test fun udpUpstreamOutOfMemoryClosesSocketAndReturnsGlobalPermit() {
        val reports = AtomicInteger()
        val opened = AtomicReference<DatagramSocket>()
        val server = Socks5Server(loopback, 0, password, upstream(opened), maxClients = 1,
            onFailure = { reports.incrementAndGet() }, workerLauncher = { worker ->
                if (worker.name == "socks-udp-upstream") throw OutOfMemoryError("synthetic pre-start failure")
                worker.start()
            })
        server.start()
        try {
            Socket(loopback, server.boundPort).use { client ->
                client.soTimeout = 1500
                Socks5.authenticateClient(DataInputStream(client.getInputStream()),
                    client.getOutputStream(), password)
                val relay = Socks5.request(DataInputStream(client.getInputStream()),
                    client.getOutputStream(), 3, Socks5.Address("0.0.0.0", 0))
                DatagramSocket().use { udp ->
                    val bytes = Socks5.encodeDatagram(Socks5.Address(loopback, 53), byteArrayOf(1))
                    udp.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(relay.host, relay.port)))
                }
                await("upstream socket must be allocated", { opened.get() != null })
                await("failed worker socket must close", { opened.get().isClosed })
                await("global UDP permit must return exactly once", { available(server, "datagramPermits") == 128 })
                await("listener must stop after upstream launch failure", { server.boundPort == 0 })
                await("control permit must return", { available(server, "permits") == 1 })
            }
            assertEquals(1, reports.get())
        } finally { server.stop() }
    }
}

package com.datanet.share.common

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

object UdpTest {
    fun runTest(onResult: (String) -> Unit, onError: (String) -> Unit, onFinished: () -> Unit) {
        Thread({
            try {
                onResult("Testing UDP through the active receiver VPN using external STUN…")
                val request = ByteArray(20)
                request[1] = 1; request[4] = 0x21; request[5] = 0x12; request[6] = 0xa4.toByte(); request[7] = 0x42
                val transaction = ByteArray(12).also(SecureRandom()::nextBytes)
                transaction.copyInto(request, 8)
                DatagramSocket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress("stun.l.google.com", 19302))
                    val started = System.nanoTime()
                    socket.send(DatagramPacket(request, request.size))
                    val response = DatagramPacket(ByteArray(1500), 1500)
                    socket.receive(response)
                    val bytes = response.data.copyOf(response.length)
                    check(bytes.size >= 20 && bytes[0] == 1.toByte() && bytes[1] == 1.toByte() &&
                        bytes.copyOfRange(4, 8).contentEquals(request.copyOfRange(4, 8)) &&
                        bytes.copyOfRange(8, 20).contentEquals(transaction)) { "Invalid STUN response" }
                    onResult("UDP STUN response: ${(System.nanoTime() - started) / 1_000_000} ms. This checks reachability, not gaming performance.")
                }
            } catch (e: Exception) { onError("UDP test failed: ${e.message}") }
            finally { onFinished() }
        }, "udp-test").apply { isDaemon = true; start() }
    }
}

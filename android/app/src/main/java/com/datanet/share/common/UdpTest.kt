package com.datanet.share.common

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

object UdpTest {
    /** Verify STUN framing, mapped address and unpredictable transaction ID. */
    internal fun isValidBindingResponse(bytes: ByteArray, transaction: ByteArray): Boolean {
        if (transaction.size != 12 || bytes.size < 20) return false
        if (bytes[0] != 0x01.toByte() || bytes[1] != 0x01.toByte()) return false
        val length = ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
        if (length % 4 != 0 || length != bytes.size - 20) return false
        if (bytes[4] != 0x21.toByte() || bytes[5] != 0x12.toByte() ||
            bytes[6] != 0xa4.toByte() || bytes[7] != 0x42.toByte()) return false
        if (!(0 until 12).all { bytes[8 + it] == transaction[it] }) return false
        // RFC 8489 Binding success must include a well-formed XOR-MAPPED-ADDRESS.
        // Other padded attributes are allowed; this is not a general STUN decoder.
        var hasMappedAddress = false
        var offset = 20
        while (offset < bytes.size) {
            if (bytes.size - offset < 4) return false
            val type = ((bytes[offset].toInt() and 255) shl 8) or
                (bytes[offset + 1].toInt() and 255)
            val valueLength = ((bytes[offset + 2].toInt() and 255) shl 8) or
                (bytes[offset + 3].toInt() and 255)
            val paddedLength = (valueLength + 3) and -4
            if (paddedLength > bytes.size - offset - 4) return false
            if (type == 0x0020) {
                val family = if (valueLength >= 2) bytes[offset + 5].toInt() and 255 else 0
                if (!((family == 1 && valueLength == 8) ||
                    (family == 2 && valueLength == 20))) return false
                hasMappedAddress = true
            }
            offset += 4 + paddedLength
        }
        return hasMappedAddress
    }

    fun runTest(onResult: (String) -> Unit, onError: (String) -> Unit, onFinished: () -> Unit) {
        runTest(onResult, onError, onFinished) { task ->
            Thread(task, "udp-test").apply { isDaemon = true; start() }
        }
    }

    // Inject only the worker launcher in JVM tests; no external STUN traffic is required.
    internal fun runTest(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onFinished: () -> Unit,
        launchWorker: (Runnable) -> Unit
    ) {
        val task = Runnable {
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
                    check(isValidBindingResponse(bytes, transaction)) { "Invalid STUN response" }
                    onResult("UDP STUN response: ${(System.nanoTime() - started) / 1_000_000} ms. This checks reachability, not gaming performance.")
                }
            } catch (_: Exception) {
                // Socket exception messages can include local network identifiers.
                onError("UDP test failed. Check the receiver VPN and network, then retry.")
            }
            finally { onFinished() }
        }
        try {
            launchWorker(task)
        } catch (_: RuntimeException) {
            reportLaunchFailure(onError, onFinished)
        } catch (_: OutOfMemoryError) {
            // Thread creation can fail with "unable to create native thread".
            reportLaunchFailure(onError, onFinished)
        }
    }

    private fun reportLaunchFailure(onError: (String) -> Unit, onFinished: () -> Unit) {
        try {
            // Never expose exception messages that may contain device or network details.
            onError("UDP test could not start. Please retry.")
        } finally {
            onFinished()
        }
    }
}

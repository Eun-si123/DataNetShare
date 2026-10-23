package com.datanet.share.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.MessageDigest

/**
 * RFC 1928/1929 framing shared by the sender, receiver and loopback tests.
 * Hostnames use printable ASCII; international names require caller-provided IDNA ASCII.
 */
object Socks5 {
    const val USERNAME = "datanet"
    data class Address(val host: String, val port: Int)
    data class Datagram(val address: Address, val payload: ByteArray)

    fun readAddress(input: DataInputStream, type: Int = input.readUnsignedByte()): Address {
        val host = when (type) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
            4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress!!
            3 -> {
                val size = input.readUnsignedByte()
                if (size == 0) throw IOException("Empty SOCKS hostname")
                val bytes = ByteArray(size).also(input::readFully)
                if (bytes.any { (it.toInt() and 255) !in 33..126 }) throw IOException("Invalid SOCKS hostname")
                String(bytes, Charsets.US_ASCII).also { host ->
                    if (ambiguousNumericHost(host)) throw IOException("Ambiguous numeric SOCKS hostname")
                }
            }
            else -> throw IOException("Unsupported SOCKS address type: $type")
        }
        return Address(host, input.readUnsignedShort())
    }

    /** Parse only canonical dotted-decimal IPv4; never use platform DNS for numeric-looking names. */
    private fun canonicalIpv4Literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = ByteArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') ||
                !part.all { it in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value.toByte()
        }
        return octets
    }

    /** Reject ambiguous all-digit/dot destinations before the sender's resolver can reinterpret them. */
    private fun ambiguousNumericHost(host: String): Boolean =
        host.isNotEmpty() && host.all { it in '0'..'9' || it == '.' } &&
            canonicalIpv4Literal(host) == null

    fun writeAddress(output: OutputStream, address: Address) {
        require(address.port in 0..65535)
        require(!ambiguousNumericHost(address.host)) { "Ambiguous numeric SOCKS hostname" }
        // Resolve IPv6 literals locally; canonical IPv4 needs no DNS.
        // Domain names are resolved by the sender's selected upstream network.
        val literal = if (address.host.contains(':')) {
            try { InetAddress.getByName(address.host).address } catch (_: Exception) { null }
        } else canonicalIpv4Literal(address.host)
        if (literal != null) {
            output.write(if (literal.size == 4) 1 else 4)
            output.write(literal)
        } else {
            // Reject non-ASCII before encoding; US_ASCII would otherwise replace characters with ?.
            require(address.host.length in 1..255 &&
                address.host.all { it.code in 33..126 }) { "Invalid SOCKS hostname" }
            val host = address.host.toByteArray(Charsets.US_ASCII)
            output.write(3); output.write(host.size); output.write(host)
        }
        output.write(address.port ushr 8); output.write(address.port and 255)
    }

    fun authenticateServer(input: DataInputStream, output: OutputStream, password: String) {
        if (input.readUnsignedByte() != 5) throw IOException("Expected SOCKS5")
        val count = input.readUnsignedByte()
        val methods = ByteArray(count).also(input::readFully)
        if (!methods.contains(2.toByte())) {
            output.write(byteArrayOf(5, 0xff.toByte())); output.flush()
            throw IOException("SOCKS5 username/password required")
        }
        output.write(byteArrayOf(5, 2)); output.flush()
        if (input.readUnsignedByte() != 1) throw IOException("Invalid authentication version")
        val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val pass = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val userOk = MessageDigest.isEqual(user, USERNAME.toByteArray(Charsets.UTF_8))
        val passOk = MessageDigest.isEqual(pass, password.toByteArray(Charsets.UTF_8))
        val ok = userOk && passOk
        output.write(byteArrayOf(1, if (ok) 0 else 1)); output.flush()
        if (!ok) throw IOException("Proxy credentials rejected")
    }

    fun authenticateClient(input: DataInputStream, output: OutputStream, password: String) {
        val useAuth = password.isNotEmpty()
        output.write(byteArrayOf(5, 1, if (useAuth) 2 else 0)); output.flush()
        val expected = if (useAuth) 2 else 0
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != expected) {
            throw IOException("Sender rejected authentication method; check proxy password")
        }
        if (useAuth) {
            val user = USERNAME.toByteArray(Charsets.UTF_8)
            val pass = password.toByteArray(Charsets.UTF_8)
            if (pass.size !in 1..255) throw IOException("Proxy password must be 1–255 UTF-8 bytes")
            output.write(byteArrayOf(1, user.size.toByte())); output.write(user)
            output.write(pass.size); output.write(pass); output.flush()
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("Proxy password rejected")
        }
    }

    fun request(input: DataInputStream, output: OutputStream, command: Int, address: Address): Address {
        output.write(byteArrayOf(5, command.toByte(), 0)); writeAddress(output, address); output.flush()
        if (input.readUnsignedByte() != 5) throw IOException("Invalid SOCKS reply version")
        val result = input.readUnsignedByte()
        if (input.readUnsignedByte() != 0) throw IOException("Invalid SOCKS reserved byte")
        val bound = readAddress(input)
        if (result != 0) throw IOException("Sender could not connect (SOCKS error $result)")
        return bound
    }

    fun reply(output: OutputStream, code: Int, bound: Address = Address("0.0.0.0", 0)) {
        output.write(byteArrayOf(5, code.toByte(), 0)); writeAddress(output, bound); output.flush()
    }

    fun encodeDatagram(address: Address, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 0, 0)); writeAddress(this, address); write(payload)
    }.toByteArray()

    fun decodeDatagram(bytes: ByteArray): Datagram? = try {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) null
        else Datagram(readAddress(input), input.readBytes())
    } catch (_: IOException) { null }
}

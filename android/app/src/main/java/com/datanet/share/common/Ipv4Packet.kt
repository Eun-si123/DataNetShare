package com.datanet.share.common

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FlowKey(val source: Int, val sourcePort: Int, val destination: Int, val destinationPort: Int) {
    val sourceBytes: ByteArray get() = ByteBuffer.allocate(4).putInt(source).array()
    val destinationBytes: ByteArray get() = ByteBuffer.allocate(4).putInt(destination).array()
}

data class Ipv4Packet(val key: FlowKey, val protocol: Int, val sequence: Int, val acknowledgement: Int,
                      val flags: Int, val window: Int, val payload: ByteArray) {
    companion object {
        fun parse(bytes: ByteArray, count: Int = bytes.size): Ipv4Packet? {
            if (count !in 20..bytes.size) return null
            val buffer = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.BIG_ENDIAN)
            val header = bytes[0].toInt() and 255
            val ihl = (header and 15) * 4
            val total = buffer.getShort(2).toInt() and 65535
            // No IP reassembly: never interpret later fragments as independent transport headers.
            if (header ushr 4 != 4 || ihl < 20 || total !in ihl..count ||
                buffer.getShort(6).toInt() and 0x3fff != 0) return null
            val protocol = bytes[9].toInt() and 255
            if (protocol != 6 && protocol != 17) return null
            if (total < ihl + if (protocol == 6) 20 else 8) return null
            val key = FlowKey(buffer.getInt(12), buffer.getShort(ihl).toInt() and 65535,
                buffer.getInt(16), buffer.getShort(ihl + 2).toInt() and 65535)
            if (protocol == 6) {
                val tcpHeader = (bytes[ihl + 12].toInt() and 255) ushr 4 shl 2
                if (tcpHeader < 20 || ihl + tcpHeader > total) return null
                return Ipv4Packet(key, 6, buffer.getInt(ihl + 4), buffer.getInt(ihl + 8),
                    bytes[ihl + 13].toInt() and 255, buffer.getShort(ihl + 14).toInt() and 65535,
                    bytes.copyOfRange(ihl + tcpHeader, total))
            }
            val udpLength = buffer.getShort(ihl + 4).toInt() and 65535
            if (udpLength < 8 || ihl + udpLength != total) return null
            return Ipv4Packet(key, 17, 0, 0, 0, 0, bytes.copyOfRange(ihl + 8, total))
        }
    }
}

/** Packet encoder for the IPv4-only experimental receiver. */
object IpPackets {
    /**
     * An option-free SYN-ACK makes Android/Linux peers fall back to an unnecessarily
     * small MSS (often 536), multiplying TCP packets during uploads. Permit safe,
     * aligned TCP options on SYN only; checksums cover options and payload.
     */
    fun tcp(key: FlowKey, seq: Int, ack: Int, flags: Int, window: Int,
            payload: ByteArray, options: ByteArray = ByteArray(0)): ByteArray {
        require(options.size <= 40 && options.size % 4 == 0) { "TCP options must be 4-byte aligned" }
        require(options.isEmpty() || flags and 0x02 != 0) { "TCP options only supported on SYN packets" }
        val transportHeader = 20 + options.size
        val packet = header(key, 6, transportHeader + payload.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(24, seq); buffer.putInt(28, ack)
        buffer.put(32, ((transportHeader / 4) shl 4).toByte())
        buffer.put(33, flags.toByte())
        buffer.putShort(34, window.coerceIn(0, 65535).toShort())
        if (options.isNotEmpty()) System.arraycopy(options, 0, packet, 40, options.size)
        System.arraycopy(payload, 0, packet, 20 + transportHeader, payload.size)
        val pseudo = ByteBuffer.allocate(12 + packet.size - 20).order(ByteOrder.BIG_ENDIAN)
            .putInt(key.destination).putInt(key.source).put(0).put(6).putShort((packet.size - 20).toShort())
            .put(packet, 20, packet.size - 20).array()
        buffer.putShort(36, checksum(pseudo).toShort())
        return packet
    }
    fun udp(key: FlowKey, payload: ByteArray): ByteArray {
        val packet = header(key, 17, 8 + payload.size)
        ByteBuffer.wrap(packet).putShort(24, (8 + payload.size).toShort())
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet // IPv4 permits an omitted UDP checksum.
    }
    private fun header(key: FlowKey, protocol: Int, transportLength: Int): ByteArray {
        val packet = ByteArray(20 + transportLength)
        ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).apply {
            put(0, 0x45); putShort(2, packet.size.toShort()); putShort(6, 0x4000)
            put(8, 64); put(9, protocol.toByte()); putInt(12, key.destination); putInt(16, key.source)
            putShort(20, key.destinationPort.toShort()); putShort(22, key.sourcePort.toShort())
            putShort(10, checksum(packet.copyOfRange(0, 20)).toShort())
        }
        return packet
    }
    fun checksum(bytes: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < bytes.size) {
            sum += ((bytes[i].toInt() and 255) shl 8) or (if (i + 1 < bytes.size) bytes[i + 1].toInt() and 255 else 0)
            i += 2
        }
        while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.inv().toInt() and 65535
    }
}

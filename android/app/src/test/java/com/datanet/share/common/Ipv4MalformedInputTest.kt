package com.datanet.share.common

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Deterministic malformed-input coverage for the IPv4-only VPN packet boundary.
 *
 * The TUN parser must discard unsupported/corrupt frames without throwing and
 * terminating the receiver read loop. These are host/JVM tests, not device
 * packet-injection or proof of IPv6/ICMP support.
 */
class Ipv4MalformedInputTest {
    private val key = FlowKey(0x0a000001, 19001, 0x08080808, 443)

    @Test fun everySingleByteMutationOfTcpAndUdpFramesIsSafe() {
        val frames = listOf(
            IpPackets.tcp(key, 7, 0, 0x02, 4096, ByteArray(17) { it.toByte() },
                byteArrayOf(1, 3, 3, 7)),
            IpPackets.udp(key, ByteArray(17) { (it * 3).toByte() })
        )
        for (original in frames) {
            assertNotNull(Ipv4Packet.parse(original))
            for (offset in original.indices) {
                val mutated = original.copyOf()
                for (value in 0..255) {
                    mutated[offset] = value.toByte()
                    assertSafeParse(mutated, mutated.size)
                }
            }
        }
    }

    @Test fun boundedRandomAndMultiByteCorruptionNeverCrashesParser() {
        val random = Random(0x4d5347)
        repeat(4_096) {
            val bytes = ByteArray(random.nextInt(0, 513))
            random.nextBytes(bytes)
            assertSafeParse(bytes, bytes.size)
            assertSafeParse(bytes, random.nextInt(0, bytes.size + 2))
        }

        val original = IpPackets.tcp(key, 10, 0, 0x02, 1024,
            ByteArray(96) { it.toByte() }, byteArrayOf(2, 4, 5, 120))
        repeat(4_096) {
            val mutated = original.copyOf()
            repeat(random.nextInt(1, 9)) {
                mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte()
            }
            assertSafeParse(mutated, mutated.size)
        }
    }

    @Test fun invalidReadCountsNeverReachPacketFields() {
        val frame = IpPackets.udp(key, byteArrayOf(1, 2, 3))
        for (count in listOf(Int.MIN_VALUE, -1, 0, 1, 19, frame.size + 1, Int.MAX_VALUE)) {
            assertTrue("invalid read count=$count", Ipv4Packet.parse(frame, count) == null)
        }
    }

    private fun assertSafeParse(bytes: ByteArray, count: Int) {
        try {
            val packet = Ipv4Packet.parse(bytes, count) ?: return
            assertTrue("unexpected transport protocol", packet.protocol == 6 || packet.protocol == 17)
            assertTrue("payload extends past supplied packet", packet.payload.size <= count)
            if (packet.protocol == 6) {
                // Malformed TCP option lengths must also be safe to inspect.
                packet.offeredMss()
                packet.offeredWindowScale()
            }
        } catch (error: Exception) {
            throw AssertionError(
                "IPv4 parser threw for length=${bytes.size}, count=$count", error
            )
        }
    }
}

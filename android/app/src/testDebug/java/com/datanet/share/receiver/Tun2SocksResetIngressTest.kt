package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** Exercises the Android bridge's real TCP dispatch, not only TcpFlow.receive(). */
class Tun2SocksResetIngressTest {
    private val key = FlowKey(0x0a000001, 55001, 0x08080808, 443)

    @Suppress("UNCHECKED_CAST")
    private fun active(bridge: Tun2Socks): ConcurrentHashMap<FlowKey, TcpFlow> =
        Tun2Socks::class.java.getDeclaredField("tcp").apply { isAccessible = true }
            .get(bridge) as ConcurrentHashMap<FlowKey, TcpFlow>

    private fun deliver(bridge: Tun2Socks, packet: Ipv4Packet) {
        Tun2Socks::class.java.getDeclaredMethod("handleTcp", Ipv4Packet::class.java)
            .apply { isAccessible = true }.invoke(bridge, packet)
    }

    @Test fun staleResetCannotRemoveLiveFlowButExactResetClosesWithoutEcho() {
        val bridge = Tun2Socks(FileDescriptor(), "127.0.0.1", 1, "synthetic",
            { true }, { true }, {}, {})
        val active = active(bridge)
        val outbound = mutableListOf<Ipv4Packet>()
        var closes = 0
        lateinit var flow: TcpFlow
        flow = TcpFlow(key, 100, "127.0.0.1", 1, "synthetic", { true },
            { outbound += Ipv4Packet.parse(it)!! }, {}, {},
            { closes++; active.remove(key, flow) }, initialSequence = 1000)
        active[key] = flow
        flow.start()
        val emittedBeforeReset = outbound.size

        deliver(bridge, Ipv4Packet(key, 6, 100, 0, 0x04, 0, byteArrayOf()))
        assertSame("stale RST must not evict the current flow", flow, active[key])
        assertEquals(0, closes)

        deliver(bridge, Ipv4Packet(key, 6, 101, 0, 0x04, 0, byteArrayOf()))
        assertFalse(active.containsKey(key))
        assertEquals(1, closes)
        assertEquals("RST must not be echoed", emittedBeforeReset, outbound.size)
        flow.close()
        assertEquals("close must remain idempotent", 1, closes)
    }
}

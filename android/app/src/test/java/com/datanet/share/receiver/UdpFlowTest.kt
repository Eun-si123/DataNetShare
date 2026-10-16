package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import com.datanet.share.sender.ProxyFixture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UdpFlowTest {
    @Test fun firstPacketsAndTwoAppsUsingSameEndpointArePreserved() = ProxyFixture().use { fixture ->
        val output = LinkedBlockingQueue<Ipv4Packet>()
        fun create(sourcePort: Int) = UdpFlow(FlowKey(0x0a000001, sourcePort, 0x7f000001, fixture.udpPort),
            "127.0.0.1", fixture.proxy.boundPort, fixture.password, { true }, { true },
            { output.add(Ipv4Packet.parse(it)!!) }, {}, {}, {})
        val a = create(10001); val b = create(10002)
        try {
            a.send("first-app".toByteArray()); b.send("second-app".toByteArray())
            Thread({ a.start() }, "test-udp-a").apply { isDaemon = true; start() }
            Thread({ b.start() }, "test-udp-b").apply { isDaemon = true; start() }
            val responses = listOf(output.poll(5, TimeUnit.SECONDS)!!, output.poll(5, TimeUnit.SECONDS)!!)
                .associateBy { it.key.destinationPort }
            assertEquals("first-app", String(responses[10001]!!.payload))
            assertEquals("second-app", String(responses[10002]!!.payload))
            a.send(ByteArray(1400) { 7 })
            assertArrayEquals(ByteArray(1400) { 7 }, output.poll(3, TimeUnit.SECONDS)!!.payload)
        } finally { a.close(); b.close() }
    }
    @Test fun closeBeforeConnectIsIdempotent() {
        var callbacks = 0
        val flow = UdpFlow(FlowKey(1, 1234, 2, 53), "127.0.0.1", 1, "x", { true }, { true }, {}, {}, {}, { callbacks++ })
        flow.close(); flow.close(); flow.start()
        assertEquals(1, callbacks)
    }
}

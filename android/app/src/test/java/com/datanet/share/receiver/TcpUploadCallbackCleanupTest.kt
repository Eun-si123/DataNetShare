package com.datanet.share.receiver

import com.datanet.share.common.FlowKey
import com.datanet.share.common.Ipv4Packet
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

class TcpUploadCallbackCleanupTest {
    @Test fun uploadFailureClosesFlowEvenIfDiagnosticCallbackThrows() {
        val failure = IllegalStateException("synthetic diagnostic failure")
        val packets = mutableListOf<Ipv4Packet>()
        val closes = AtomicInteger()
        val errors = AtomicInteger()
        val flow = TcpFlow(FlowKey(0x0a000001, 55002, 0x08080808, 443),
            100, "127.0.0.1", 1, "unused", { true },
            { packets += Ipv4Packet.parse(it)!! }, {}, {},
            { closes.incrementAndGet() }, initialSequence = 1000,
            onError = { errors.incrementAndGet(); throw failure })
        flow.start()
        val brokenSocket = object : Socket() {
            override fun getOutputStream(): OutputStream = throw IOException("synthetic write failure")
        }
        val writer = TcpFlow::class.java.getDeclaredMethod("writer", Socket::class.java).apply {
            isAccessible = true
        }
        try {
            writer.invoke(flow, brokenSocket)
            fail("diagnostic callback should propagate")
        } catch (actual: InvocationTargetException) {
            assertSame(failure, actual.cause)
        } finally {
            brokenSocket.close()
        }
        assertEquals(1, errors.get())
        assertEquals(1, closes.get())
        assertEquals(listOf(0x12, 0x14), packets.map { it.flags })
        flow.abort()
        assertEquals(1, closes.get())
    }
}

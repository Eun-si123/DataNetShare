package com.datanet.share.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Socks5RestartIsolationTest {
    @Test fun oldAcceptLoopMustNotStopNewListenerAfterRestart() {
        // Hold the old accept thread inside a rejected-client callback so Stop
        // and Start happen before that thread resumes. This deterministically
        // exposes stale accept loops that consult only a shared 'running' flag.
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val previousAccept = AtomicReference<Thread>()
        val upstream = object : Socks5Server.Upstream {
            override fun connect(host: String, port: Int): Socket = throw IOException("unused")
            override fun resolve(host: String): Array<InetAddress> = throw IOException("unused")
            override fun datagram(): DatagramSocket = throw IOException("unused")
        }
        val proxy = Socks5Server(
            "127.0.0.1", 0, "test-pass", upstream,
            onClientLimit = {
                previousAccept.set(Thread.currentThread())
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS)) { "test callback was not released" }
            },
            maxClients = 1
        )
        proxy.start()
        try {
            Socket("127.0.0.1", proxy.boundPort).use { first ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (proxy.clientCount != 1 && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                assertEquals("first client must occupy the only slot", 1, proxy.clientCount)
                Socket("127.0.0.1", proxy.boundPort).use {
                    assertTrue("old accept callback must be entered", entered.await(3, TimeUnit.SECONDS))
                    proxy.stop()
                    proxy.start()
                    resume.countDown()
                    val oldThread = previousAccept.get()
                    oldThread.join(3000)
                    assertFalse("old accept thread must terminate", oldThread.isAlive)
                    assertTrue("old accept must not close the restarted listener", proxy.boundPort > 0)
                }
            }
        } finally {
            resume.countDown()
            proxy.stop()
        }
    }
}

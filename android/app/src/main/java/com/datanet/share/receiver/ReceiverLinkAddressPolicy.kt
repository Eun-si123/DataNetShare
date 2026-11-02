package com.datanet.share.receiver

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Match a sender to a local IPv4 link prefix before binding protected SOCKS sockets.
 * A matching prefix is necessary but not sufficient: ReceiverLink separately rejects
 * VPN/cellular networks and virtual fallback interfaces.
 */
internal object ReceiverLinkAddressPolicy {
    fun sameIpv4Prefix(local: InetAddress, peer: InetAddress, prefixLength: Int): Boolean {
        if (local !is Inet4Address || peer !is Inet4Address || prefixLength !in 1..32) return false
        val a = ByteBuffer.wrap(local.address).int
        val b = ByteBuffer.wrap(peer.address).int
        val mask = -1 shl (32 - prefixLength)
        return (a and mask) == (b and mask)
    }
}

package com.datanet.share.common

/** Defensive filter until upstream UDP relay gets a real per-flow NAT mapping. */
object UdpMappingPolicy {
    fun isSameClient(existingIp: ByteArray, existingPort: Int, incomingIp: ByteArray, incomingPort: Int): Boolean =
        existingPort == incomingPort && existingIp.contentEquals(incomingIp)
}

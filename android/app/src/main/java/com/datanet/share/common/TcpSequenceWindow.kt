package com.datanet.share.common

/** Strict sequence acceptance for the receiver's minimal TCP bridge.
 * Out-of-order data is rejected (and should be retransmitted by the peer).
 * This does not implement sender-side TCP retransmission or congestion control.
 */
object TcpSequenceWindow {
    private const val MASK = 0xFFFFFFFFL

    fun advanceIfInOrder(expectedNext: Long, packetSeq: Int, payloadBytes: Int): Long? {
        if (payloadBytes <= 0) return null
        val actualSeq = packetSeq.toLong() and MASK
        if (actualSeq != (expectedNext and MASK)) return null
        return (expectedNext + payloadBytes) and MASK
    }
}

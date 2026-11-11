package com.datanet.share.receiver

/** A delayed VPN revoke must not tear down a replacement receiver session. */
internal object ReceiverRevocationGeneration {
    fun accepts(captured: Int, current: Int): Boolean = captured == current
}

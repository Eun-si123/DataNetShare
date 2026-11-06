package com.datanet.share.receiver

import com.datanet.share.common.NetworkEvent

/** Keep receiver UDP diagnostics consistent with the actual failed stage. */
internal object ReceiverUdpErrorPolicy {
    fun classify(error: String): NetworkEvent = when {
        error.contains("capacity") -> NetworkEvent.UDP_FLOW_LIMIT
        error.contains("queue full") -> NetworkEvent.UDP_QUEUE
        error.contains("read") || error.startsWith("UDP receive callback failed (") -> NetworkEvent.UDP_RELAY
        else -> NetworkEvent.UDP_SETUP
    }
}

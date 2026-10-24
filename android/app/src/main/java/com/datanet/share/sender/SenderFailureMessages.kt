package com.datanet.share.sender

import java.net.BindException

/** User-facing failure hints must never include platform exception or socket details. */
internal object SenderFailureMessages {
    fun startup(error: Exception): String = when (error) {
        is SecurityException -> "Sharing permission denied. Check Android permissions and retry."
        is IllegalArgumentException -> "Sharing settings were rejected. Check the selected network and password."
        else -> "Sharing could not start. Check network availability and retry."
    }

    fun proxyStart(error: Exception): String = when (error) {
        is BindException -> "Proxy could not start: the local address or port is already in use."
        is SecurityException -> "Proxy could not start: check Android network permissions."
        else -> "Proxy could not start. Check the local connection and retry."
    }

    const val LISTENER_STOPPED = "Proxy listener stopped unexpectedly. Stop and restart sharing."
}

package com.datanet.share

/** Pure presentation logic for one connection action, independent of Android views. */
internal enum class ConnectionRole { SHARE, RECEIVE }
internal enum class SharingLink { WIFI_DIRECT, LOCAL }
internal enum class ConnectionAction { START_DIRECT, START_LOCAL, START_RECEIVER, STOP }

internal object ConnectionControls {
    fun action(busy: Boolean, role: ConnectionRole, link: SharingLink): ConnectionAction = when {
        busy -> ConnectionAction.STOP
        role == ConnectionRole.RECEIVE -> ConnectionAction.START_RECEIVER
        link == SharingLink.LOCAL -> ConnectionAction.START_LOCAL
        else -> ConnectionAction.START_DIRECT
    }
}

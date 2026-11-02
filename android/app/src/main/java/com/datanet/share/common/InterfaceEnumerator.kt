package com.datanet.share.common

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object InterfaceEnumerator {
    private const val TAG = "InterfaceEnumerator"
    data class InterfaceInfo(val name: String, val displayName: String, val ip: String, val type: InterfaceType)
    enum class InterfaceType { WIFI_DIRECT, USB_TETHERING, WIFI, CELLULAR, OTHER }

    fun getAllInterfaces(): List<InterfaceInfo> {
        val result = mutableListOf<InterfaceInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name ?: continue
                val type = classifyInterface(name)
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result.add(InterfaceInfo(name, iface.displayName ?: name, addr.hostAddress ?: continue, type))
                    }
                }
            }
        } catch (_: Exception) { Log.w(TAG, "Failed to enumerate interfaces") }
        return result.sortedBy { it.type.ordinal }
    }

    private fun classifyInterface(name: String): InterfaceType {
        val n = name.lowercase()
        return when {
            n.startsWith("p2p") -> InterfaceType.WIFI_DIRECT
            n.contains("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> InterfaceType.USB_TETHERING
            n.startsWith("wlan") -> InterfaceType.WIFI
            n.startsWith("rmnet") || n.startsWith("ccmni") -> InterfaceType.CELLULAR
            else -> InterfaceType.OTHER
        }
    }

    /**
     * A private IPv4 address alone does not make a virtual VPN/CLAT interface
     * a safe local sharing link. Keep unfamiliar OEM physical-link names
     * eligible, but do not offer known tunnel adapters as sender bind targets.
     */
    internal fun isLocalCandidate(info: InterfaceInfo): Boolean {
        val name = info.name.lowercase(Locale.ROOT)
        if (listOf("tun", "tap", "wg", "ipsec", "ppp", "veth", "dummy", "tailscale", "v4-")
                .any(name::startsWith)) return false
        return info.type in listOf(InterfaceType.WIFI, InterfaceType.WIFI_DIRECT, InterfaceType.USB_TETHERING) ||
            (info.type == InterfaceType.OTHER && java.net.InetAddress.getByName(info.ip).isSiteLocalAddress)
    }

    fun localCandidates(): List<InterfaceInfo> = getAllInterfaces().filter(::isLocalCandidate)

    fun getSummary(): String {
        val ifaces = getAllInterfaces()
        if (ifaces.isEmpty()) return "No network interfaces available"
        val sb = StringBuilder()
        for (iface in ifaces) {
            val typeLabel = when (iface.type) {
                InterfaceType.WIFI_DIRECT -> "WiFi Direct"
                InterfaceType.USB_TETHERING -> "USB Tethering"
                InterfaceType.WIFI -> "WiFi"
                InterfaceType.CELLULAR -> "Cellular"
                InterfaceType.OTHER -> "Other"
            }
            sb.append("$typeLabel (${iface.name}): ${iface.ip}\n")
        }
        return sb.toString().trimEnd('\n')
    }
}

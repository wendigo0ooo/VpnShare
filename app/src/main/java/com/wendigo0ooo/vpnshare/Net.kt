package com.wendigo0ooo.vpnshare

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {

    private val preferredOrder = listOf("p2p", "ap", "swlan", "wlan", "eth", "wl")
    private val excluded = listOf("tun", "rmnet", "dummy", "lo")

    fun allIpv4(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (nif in interfaces) {
                val name = (nif.name ?: "").lowercase()
                if (!nif.isUp || nif.isLoopback) continue
                if (excluded.any { name.startsWith(it) }) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("169.254.")) continue
                        result.add(name to host)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun bestIp(): String? {
        val all = allIpv4()
        if (all.isEmpty()) return null
        for (prefix in preferredOrder) {
            all.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
        }
        return all.first().second
    }
}

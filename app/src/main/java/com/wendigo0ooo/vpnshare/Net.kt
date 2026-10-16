package com.wendigo0ooo.vpnshare

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {

    private val preferredOrder = listOf("p2p", "ap", "swlan", "wlan", "eth", "wl")
    private val excluded = listOf(
        "tun", "rmnet", "ccmni", "ppp", "dummy", "lo", "clat", "sit", "radio", "pdp", "ip6tnl"
    )

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

    fun ipForPrefixes(prefixes: List<String>): String? {
        val all = allIpv4()
        for (prefix in prefixes) {
            all.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
        }
        return null
    }

    fun waitForPrefixes(prefixes: List<String>, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ipForPrefixes(prefixes)?.let { return it }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                break
            }
        }
        return ipForPrefixes(prefixes)
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

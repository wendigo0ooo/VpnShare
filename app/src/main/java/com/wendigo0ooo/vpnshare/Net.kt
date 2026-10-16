package com.wendigo0ooo.vpnshare

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {

    fun localIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: ""
                if (name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("p2p")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("169.254.")) continue
                        return host
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}

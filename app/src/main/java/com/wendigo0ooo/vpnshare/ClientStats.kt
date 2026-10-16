package com.wendigo0ooo.vpnshare

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ClientInfo(val ip: String) {
    @Volatile
    var type: String? = null

    @Volatile
    var hostname: String? = null

    @Volatile
    var userAgent: String? = null

    val connections = AtomicInteger(0)
    val active = AtomicInteger(0)
    val up = AtomicLong(0)
    val down = AtomicLong(0)

    @Volatile
    var firstSeen = System.currentTimeMillis()

    @Volatile
    var lastSeen = System.currentTimeMillis()
}

object ClientStats {

    private val map = ConcurrentHashMap<String, ClientInfo>()

    fun onOpen(ip: String): ClientInfo {
        val isNew = !map.containsKey(ip)
        val info = map.getOrPut(ip) { ClientInfo(ip) }
        info.active.incrementAndGet()
        info.connections.incrementAndGet()
        info.lastSeen = System.currentTimeMillis()
        if (isNew) resolveHostname(info)
        return info
    }

    fun onClose(ip: String) {
        map[ip]?.let {
            if (it.active.get() > 0) it.active.decrementAndGet()
            it.lastSeen = System.currentTimeMillis()
        }
    }

    fun addUp(ip: String?, bytes: Long) {
        if (ip == null) return
        map[ip]?.let {
            it.up.addAndGet(bytes)
            it.lastSeen = System.currentTimeMillis()
        }
    }

    fun addDown(ip: String?, bytes: Long) {
        if (ip == null) return
        map[ip]?.let {
            it.down.addAndGet(bytes)
            it.lastSeen = System.currentTimeMillis()
        }
    }

    fun noteUserAgent(ip: String, ua: String?) {
        if (ua.isNullOrBlank()) return
        val info = map.getOrPut(ip) { ClientInfo(ip) }
        if (info.userAgent == null) info.userAgent = ua
        if (info.type == null) guessFromUserAgent(ua)?.let { info.type = it }
    }

    fun noteHost(ip: String, host: String?) {
        if (host.isNullOrBlank()) return
        val info = map.getOrPut(ip) { ClientInfo(ip) }
        if (info.type == null) guessFromHost(host)?.let { info.type = it }
    }

    fun snapshot(): List<ClientInfo> = map.values.sortedByDescending { it.lastSeen }

    fun activeCount(): Int = map.values.count { it.active.get() > 0 }

    fun totalCount(): Int = map.size

    fun reset() = map.clear()

    private fun resolveHostname(info: ClientInfo) {
        Thread {
            try {
                val host = InetAddress.getByName(info.ip).canonicalHostName
                if (!host.isNullOrBlank() && host != info.ip) info.hostname = host
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    private fun guessFromUserAgent(ua: String): String? {
        val u = ua.lowercase()
        return when {
            "iphone" in u -> "iPhone"
            "ipad" in u -> "iPad"
            "ipod" in u -> "iPod"
            "android" in u -> "Android"
            "windows" in u -> "Windows"
            "macintosh" in u || "mac os x" in u -> "Mac"
            "cros" in u -> "ChromeOS"
            "linux" in u -> "Linux"
            "curl" in u -> "curl"
            "dalvik" in u -> "Android"
            else -> null
        }
    }

    private fun guessFromHost(host: String): String? {
        val h = host.lowercase()
        return when {
            "captive.apple.com" in h -> "iOS"
            "connectivitycheck" in h || "clients3.google.com" in h ||
                "client3.google.com" in h || "generate_204" in h -> "Android"
            "msftconnecttest" in h || "msftncsi" in h -> "Windows"
            "detectportal.firefox" in h -> "Firefox"
            else -> null
        }
    }
}

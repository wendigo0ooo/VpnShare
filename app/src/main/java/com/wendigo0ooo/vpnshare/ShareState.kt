package com.wendigo0ooo.vpnshare

object ShareState {
    const val MODE_LAN = "LAN"
    const val MODE_P2P = "P2P"
    const val MODE_HOTSPOT = "HOTSPOT"

    const val HTTP_PORT = 8282
    const val SOCKS_PORT = 1080

    @Volatile
    var running = false

    @Volatile
    var preparing = false

    @Volatile
    var mode: String = MODE_P2P

    @Volatile
    var ssid: String? = null

    @Volatile
    var password: String? = null

    @Volatile
    var proxyIp: String? = null

    @Volatile
    var error: String? = null

    fun reset() {
        running = false
        preparing = false
        ssid = null
        password = null
        proxyIp = null
        error = null
    }
}

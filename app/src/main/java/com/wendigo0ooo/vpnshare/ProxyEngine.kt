package com.wendigo0ooo.vpnshare

class ProxyEngine(private val httpPort: Int, private val socksPort: Int) {

    private var http: HttpProxyServer? = null
    private var socks: Socks5Server? = null

    fun start() {
        http = try {
            HttpProxyServer(httpPort).also { it.start() }
        } catch (_: Exception) {
            null
        }
        socks = try {
            Socks5Server(socksPort).also { it.start() }
        } catch (_: Exception) {
            null
        }
    }

    fun stop() {
        http?.stop()
        http = null
        socks?.stop()
        socks = null
    }
}

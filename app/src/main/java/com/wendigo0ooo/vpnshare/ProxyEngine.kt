package com.wendigo0ooo.vpnshare

class ProxyEngine(
    private val preferredHttpPort: Int,
    private val preferredSocksPort: Int
) {

    private var http: HttpProxyServer? = null
    private var socks: Socks5Server? = null

    var httpPort = -1
        private set

    var socksPort = -1
        private set

    val httpReady: Boolean get() = httpPort > 0
    val socksReady: Boolean get() = socksPort > 0

    fun start() {
        http = bindHttp(preferredHttpPort)
        http?.let { httpPort = it.boundPort }

        socks = bindSocks(preferredSocksPort)
        socks?.let { socksPort = it.boundPort }
    }

    private fun bindHttp(preferred: Int): HttpProxyServer? {
        for (port in candidatePorts(preferred)) {
            try {
                return HttpProxyServer(port).also { it.start() }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun bindSocks(preferred: Int): Socks5Server? {
        for (port in candidatePorts(preferred)) {
            try {
                return Socks5Server(port).also { it.start() }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun candidatePorts(preferred: Int): List<Int> =
        if (preferred in 1024..65535) listOf(preferred, 0) else listOf(0)

    fun stop() {
        http?.stop()
        http = null
        socks?.stop()
        socks = null
        httpPort = -1
        socksPort = -1
    }
}

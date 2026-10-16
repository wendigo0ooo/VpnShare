package com.wendigo0ooo.vpnshare

import android.util.Base64
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpProxyServer(private val port: Int) {

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", port))
        serverSocket = ss
        running = true
        Thread {
            while (running) {
                try {
                    val client = ss.accept()
                    val ip = client.inetAddress?.hostAddress
                    pool.execute { handle(client, ip) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }.apply {
            isDaemon = true
            name = "http-accept"
        }.start()
    }

    fun stop() {
        running = false
        Relay.closeQuietly(serverSocket)
        pool.shutdownNow()
    }

    private fun handle(client: Socket, ip: String?) {
        if (BlockList.isBlocked(ip)) {
            Relay.closeQuietly(client)
            return
        }
        if (ip != null) {
            ClientStats.onOpen(ip)
            ConnectionRegistry.register(ip, client)
        }
        val counted = AtomicBoolean(false)
        val finish: () -> Unit = {
            if (counted.compareAndSet(false, true) && ip != null) {
                ClientStats.onClose(ip)
                ConnectionRegistry.unregister(ip, client)
            }
        }
        var relayed = false
        try {
            client.tcpNoDelay = true
            val ins = BufferedInputStream(client.getInputStream())
            val outs = client.getOutputStream()

            val requestLine = readLine(ins) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeError(outs, 400, "Bad Request")
                return
            }
            val method = parts[0]
            val target = parts[1]

            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
                }
            }

            if (!authOk(headers)) {
                writeAuthRequired(outs)
                return
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                val (host, dstPort) = splitHostPort(target, 443)
                if (ip != null) ClientStats.noteHost(ip, host)
                val remote = Socket()
                try {
                    remote.connect(InetSocketAddress(host, dstPort), 15000)
                } catch (_: Exception) {
                    writeError(outs, 502, "Bad Gateway")
                    return
                }
                remote.tcpNoDelay = true
                outs.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                outs.flush()
                relayed = true
                Relay.pipe(client, remote, ins, ip) { finish() }
                return
            }

            val uri = URI(target)
            val host = uri.host
            if (host == null) {
                writeError(outs, 400, "Bad Request")
                return
            }
            if (ip != null) {
                ClientStats.noteHost(ip, host)
                headers.firstOrNull { it.first.equals("User-Agent", ignoreCase = true) }
                    ?.let { ClientStats.noteUserAgent(ip, it.second) }
            }
            val dstPort = if (uri.port != -1) uri.port else 80
            val path = (if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath) +
                (uri.rawQuery?.let { "?$it" } ?: "")

            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(host, dstPort), 15000)
            } catch (_: Exception) {
                writeError(outs, 502, "Bad Gateway")
                return
            }
            remote.tcpNoDelay = true

            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            for ((k, v) in headers) {
                val lk = k.lowercase()
                if (lk == "proxy-connection" || lk == "connection" || lk == "proxy-authorization") continue
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")

            val rout = remote.getOutputStream()
            rout.write(sb.toString().toByteArray())
            rout.flush()

            relayed = true
            Relay.pipe(client, remote, ins, ip) { finish() }
        } catch (_: Exception) {
        } finally {
            if (!relayed) {
                Relay.closeQuietly(client)
                finish()
            }
        }
    }

    private fun authOk(headers: List<Pair<String, String>>): Boolean {
        if (!ProxyAuth.enabled) return true
        val value = headers.firstOrNull { it.first.equals("Proxy-Authorization", ignoreCase = true) }
            ?.second ?: return false
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(value.substring(6).trim(), Base64.DEFAULT))
        } catch (_: Exception) {
            return false
        }
        val idx = decoded.indexOf(':')
        if (idx < 0) return false
        return ProxyAuth.check(decoded.substring(0, idx), decoded.substring(idx + 1))
    }

    private fun writeAuthRequired(outs: OutputStream) {
        try {
            outs.write(
                ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"vpnshare\"\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            outs.flush()
        } catch (_: Exception) {
        }
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        val idx = value.lastIndexOf(':')
        if (idx < 0) return value.trim() to defaultPort
        val host = value.substring(0, idx).trim().trimStart('[').trimEnd(']')
        val port = value.substring(idx + 1).toIntOrNull() ?: defaultPort
        return host to port
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        var readAny = false
        while (true) {
            val c = ins.read()
            if (c == -1) return if (readAny) sb.toString() else null
            readAny = true
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun writeError(outs: OutputStream, code: Int, message: String) {
        try {
            val body = "$code $message"
            outs.write(
                ("HTTP/1.1 $code $message\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body).toByteArray()
            )
            outs.flush()
        } catch (_: Exception) {
        }
    }
}

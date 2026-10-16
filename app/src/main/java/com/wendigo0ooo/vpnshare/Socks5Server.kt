package com.wendigo0ooo.vpnshare

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Socks5Server(private val port: Int) {

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
            name = "socks5-accept"
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
            val ins = client.getInputStream()
            val outs = client.getOutputStream()

            val version = ins.read()
            if (version != 5) return
            val nMethods = ins.read()
            if (nMethods <= 0) return
            val methods = ByteArray(nMethods)
            readFully(ins, methods)

            outs.write(byteArrayOf(5, 0))
            outs.flush()

            val rVersion = ins.read()
            if (rVersion != 5) return
            val cmd = ins.read()
            ins.read() // reserved
            val atyp = ins.read()

            val host: String
            when (atyp) {
                1 -> {
                    val b = ByteArray(4)
                    readFully(ins, b)
                    host = b.joinToString(".") { (it.toInt() and 0xff).toString() }
                }
                3 -> {
                    val len = ins.read()
                    if (len <= 0) return
                    val b = ByteArray(len)
                    readFully(ins, b)
                    host = String(b, Charsets.US_ASCII)
                }
                4 -> {
                    val b = ByteArray(16)
                    readFully(ins, b)
                    host = InetAddress.getByAddress(b).hostAddress ?: ""
                }
                else -> return
            }

            val portHi = ins.read()
            val portLo = ins.read()
            val dstPort = (portHi shl 8) or portLo

            if (cmd != 1) {
                outs.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                outs.flush()
                return
            }
            if (host.isEmpty()) {
                outs.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                outs.flush()
                return
            }
            if (ip != null) ClientStats.noteHost(ip, host)

            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(host, dstPort), 15000)
            } catch (_: Exception) {
                outs.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                outs.flush()
                return
            }
            remote.tcpNoDelay = true

            outs.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            outs.flush()

            relayed = true
            Relay.pipe(client, remote, null, ip) { finish() }
        } catch (_: Exception) {
        } finally {
            if (!relayed) {
                Relay.closeQuietly(client)
                finish()
            }
        }
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }
}

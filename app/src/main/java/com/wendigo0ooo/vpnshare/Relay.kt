package com.wendigo0ooo.vpnshare

import java.io.InputStream
import java.net.Socket

object Relay {

    fun pipe(client: Socket, remote: Socket, clientIn: InputStream? = null) {
        val cin = clientIn ?: client.getInputStream()
        val cout = client.getOutputStream()
        val rin = remote.getInputStream()
        val rout = remote.getOutputStream()

        val t1 = Thread {
            copyStream(cin, rout)
            closeQuietly(remote)
            closeQuietly(client)
        }
        t1.isDaemon = true

        val t2 = Thread {
            copyStream(rin, cout)
            closeQuietly(client)
            closeQuietly(remote)
        }
        t2.isDaemon = true

        t1.start()
        t2.start()
    }

    private fun copyStream(from: InputStream, to: java.io.OutputStream) {
        try {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        }
    }

    fun closeQuietly(c: java.io.Closeable?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }
}

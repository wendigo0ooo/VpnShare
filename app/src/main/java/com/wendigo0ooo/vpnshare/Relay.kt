package com.wendigo0ooo.vpnshare

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

object Relay {

    fun pipe(
        client: Socket,
        remote: Socket,
        clientIn: InputStream? = null,
        clientIp: String? = null,
        onDone: (() -> Unit)? = null
    ) {
        val cin = clientIn ?: client.getInputStream()
        val cout = client.getOutputStream()
        val rin = remote.getInputStream()
        val rout = remote.getOutputStream()

        val remaining = AtomicInteger(2)
        val finish: () -> Unit = {
            if (remaining.decrementAndGet() == 0) onDone?.invoke()
        }

        Thread {
            copyStream(cin, rout) { if (clientIp != null) ClientStats.addUp(clientIp, it) }
            closeQuietly(remote)
            closeQuietly(client)
            finish()
        }.apply { isDaemon = true }.start()

        Thread {
            copyStream(rin, cout) { if (clientIp != null) ClientStats.addDown(clientIp, it) }
            closeQuietly(client)
            closeQuietly(remote)
            finish()
        }.apply { isDaemon = true }.start()
    }

    private inline fun copyStream(from: InputStream, to: OutputStream, onBytes: (Long) -> Unit) {
        try {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
                onBytes(n.toLong())
            }
        } catch (_: Exception) {
        }
    }

    fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }
}

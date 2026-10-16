package com.wendigo0ooo.vpnshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ProxyService : Service() {

    private var httpServer: HttpProxyServer? = null
    private var socksServer: Socks5Server? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (httpServer == null && socksServer == null) {
            val ip = Net.localIp() ?: "0.0.0.0"
            val notification = buildNotification(ip)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }

            httpServer = try {
                HttpProxyServer(HTTP_PORT).also { it.start() }
            } catch (_: Exception) {
                null
            }
            socksServer = try {
                Socks5Server(SOCKS_PORT).also { it.start() }
            } catch (_: Exception) {
                null
            }
            isRunning = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        socksServer?.stop()
        socksServer = null
        isRunning = false
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, ip, HTTP_PORT, SOCKS_PORT))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    companion object {
        const val HTTP_PORT = 8080
        const val SOCKS_PORT = 1080
        const val ACTION_STOP = "com.wendigo0ooo.vpnshare.action.STOP"

        private const val CHANNEL_ID = "vpnshare"
        private const val NOTIF_ID = 1

        @Volatile
        var isRunning = false
    }
}

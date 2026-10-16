package com.wendigo0ooo.vpnshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ShareService : Service() {

    private var engine: ProxyEngine? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundCompat()
                val mode = intent.getStringExtra(EXTRA_MODE) ?: ShareState.MODE_P2P
                startMode(mode)
            }
        }
        return START_STICKY
    }

    private fun startMode(mode: String) {
        stopEngine()
        stopHotspotAndP2p()
        ConnectionRegistry.reset()
        BlockList.reset()
        ClientStats.reset()
        ShareState.reset()
        ShareState.mode = mode
        ShareState.preparing = true
        updateNotification()

        when (mode) {
            ShareState.MODE_P2P -> startP2p()
            ShareState.MODE_HOTSPOT -> startHotspot()
            else -> startLan()
        }
    }

    private fun startLan() {
        Thread { startEngine(resolveShareIp(ShareState.MODE_LAN)) }.start()
    }

    private fun resolveShareIp(mode: String): String = when (mode) {
        ShareState.MODE_P2P -> Net.waitForPrefixes(listOf("p2p"), 6000) ?: "192.168.49.1"
        ShareState.MODE_HOTSPOT ->
            Net.waitForPrefixes(listOf("ap", "swlan", "wlan"), 6000) ?: "192.168.43.1"
        else -> Net.bestIp() ?: "0.0.0.0"
    }

    private fun startEngine(ip: String) {
        try {
            engine = ProxyEngine(ShareState.HTTP_PORT, ShareState.SOCKS_PORT).also { it.start() }
        } catch (_: Exception) {
            engine = null
        }
        ShareState.proxyIp = ip
        ShareState.preparing = false
        ShareState.running = true
        updateNotification()
    }

    private fun startHotspot() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            fail("Wi-Fi servisi bulunamadı")
            return
        }
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        var ssid: String? = null
                        var pass: String? = null
                        try {
                            if (Build.VERSION.SDK_INT >= 30) {
                                val cfg = res.softApConfiguration
                                ssid = cfg.ssid
                                pass = cfg.passphrase
                            } else {
                                @Suppress("DEPRECATION")
                                val cfg = res.wifiConfiguration
                                ssid = cfg?.SSID
                                @Suppress("DEPRECATION")
                                pass = cfg?.preSharedKey
                            }
                        } catch (_: Exception) {
                        }
                        ShareState.ssid = ssid
                        ShareState.password = pass
                        Thread { startEngine(resolveShareIp(ShareState.MODE_HOTSPOT)) }.start()
                    }

                    override fun onFailed(reason: Int) {
                        fail("Hotspot başlatılamadı (kod $reason)")
                    }

                    override fun onStopped() {
                    }
                },
                Handler(mainLooper)
            )
        } catch (e: Exception) {
            fail("Hotspot hatası: ${e.message}")
        }
    }

    private fun startP2p() {
        val manager = applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            fail("Wi-Fi Direct desteklenmiyor")
            return
        }
        p2pManager = manager
        val channel = manager.initialize(this, mainLooper, null)
        p2pChannel = channel

        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestGroupInfo(channel) { group ->
                    if (group != null) {
                        ShareState.ssid = group.networkName
                        ShareState.password = group.passphrase
                    }
                    Thread { startEngine(resolveShareIp(ShareState.MODE_P2P)) }.start()
                }
            }

            override fun onFailure(reason: Int) {
                fail("Wi-Fi Direct grubu kurulamadı (kod $reason). Wi-Fi açık mı?")
            }
        })
    }

    private fun fail(message: String) {
        ShareState.error = message
        ShareState.preparing = false
        ShareState.running = false
        updateNotification()
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
    }

    private fun stopHotspotAndP2p() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null

        try {
            val manager = p2pManager
            val channel = p2pChannel
            if (manager != null && channel != null) {
                manager.removeGroup(channel, null)
            }
        } catch (_: Exception) {
        }
        p2pManager = null
        p2pChannel = null
    }

    private fun stopEverything() {
        stopEngine()
        stopHotspotAndP2p()
        ConnectionRegistry.reset()
        BlockList.reset()
        ShareState.reset()
        ClientStats.reset()
    }

    override fun onDestroy() {
        stopEverything()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
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

    private fun modeLabel(): String = when (ShareState.mode) {
        ShareState.MODE_P2P -> "Wi-Fi Direct"
        ShareState.MODE_HOTSPOT -> "Hotspot"
        else -> "Aynı Ağ"
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            ShareState.error != null -> ShareState.error
            ShareState.running ->
                "${modeLabel()} • ${ShareState.proxyIp}:${ShareState.HTTP_PORT}" +
                    (ShareState.ssid?.let { " • $it" } ?: "")
            else -> getString(R.string.notif_preparing)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.wendigo0ooo.vpnshare.action.START"
        const val ACTION_STOP = "com.wendigo0ooo.vpnshare.action.STOP"
        const val EXTRA_MODE = "mode"

        private const val CHANNEL_ID = "vpnshare"
        private const val NOTIF_ID = 1
    }
}

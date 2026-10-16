package com.wendigo0ooo.vpnshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)
        toggleButton = findViewById(R.id.toggleButton)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1
            )
        }

        toggleButton.setOnClickListener {
            val intent = Intent(this, ProxyService::class.java)
            if (ProxyService.isRunning) {
                stopService(intent)
            } else {
                ContextCompat.startForegroundService(this, intent)
            }
            statusText.postDelayed({ updateUi() }, 300)
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val ip = Net.localIp() ?: "-"
        infoText.text = getString(
            R.string.info_text,
            ip,
            ProxyService.HTTP_PORT,
            ProxyService.SOCKS_PORT
        )
        if (ProxyService.isRunning) {
            statusText.text = getString(R.string.status_running)
            toggleButton.text = getString(R.string.stop)
        } else {
            statusText.text = getString(R.string.status_stopped)
            toggleButton.text = getString(R.string.start)
        }
    }
}

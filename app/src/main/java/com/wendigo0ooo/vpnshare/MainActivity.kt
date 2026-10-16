package com.wendigo0ooo.vpnshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {

    private lateinit var modeGroup: RadioGroup
    private lateinit var modeP2p: RadioButton
    private lateinit var modeHotspot: RadioButton
    private lateinit var modeLan: RadioButton
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var infoText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeGroup = findViewById(R.id.modeGroup)
        modeP2p = findViewById(R.id.modeP2p)
        modeHotspot = findViewById(R.id.modeHotspot)
        modeLan = findViewById(R.id.modeLan)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)
        infoText = findViewById(R.id.infoText)
        qrImage = findViewById(R.id.qrImage)
        qrHint = findViewById(R.id.qrHint)

        requestPermissions()

        toggleButton.setOnClickListener {
            if (ShareState.running || ShareState.preparing) {
                val intent = Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP)
                startService(intent)
            } else {
                val mode = when (modeGroup.checkedRadioButtonId) {
                    R.id.modeHotspot -> ShareState.MODE_HOTSPOT
                    R.id.modeLan -> ShareState.MODE_LAN
                    else -> ShareState.MODE_P2P
                }
                val intent = Intent(this, ShareService::class.java)
                    .setAction(ShareService.ACTION_START)
                    .putExtra(ShareService.EXTRA_MODE, mode)
                ContextCompat.startForegroundService(this, intent)
            }
            handler.postDelayed({ updateUi() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun requestPermissions() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val toRequest = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1)
        }
    }

    private fun updateUi() {
        val running = ShareState.running
        val preparing = ShareState.preparing

        if (running) {
            statusText.text = getString(R.string.status_running)
            toggleButton.text = getString(R.string.stop)
        } else if (preparing) {
            statusText.text = getString(R.string.status_starting)
            toggleButton.text = getString(R.string.stop)
        } else {
            statusText.text = getString(R.string.status_stopped)
            toggleButton.text = getString(R.string.start)
        }

        for (id in listOf(R.id.modeP2p, R.id.modeHotspot, R.id.modeLan)) {
            modeGroup.findViewById<RadioButton>(id).isEnabled = !running && !preparing
        }

        ShareState.error?.let {
            errorText.visibility = TextView.VISIBLE
            errorText.text = it
        } ?: run {
            errorText.visibility = TextView.GONE
        }

        val sb = StringBuilder()
        val ip = ShareState.proxyIp
        ShareState.ssid?.let { sb.append(getString(R.string.label_ssid, it)).append('\n') }
        ShareState.password?.let { sb.append(getString(R.string.label_pass, it)).append('\n') }
        if (ip != null) {
            sb.append('\n')
            sb.append(getString(R.string.label_http, ip, ShareState.HTTP_PORT)).append('\n')
            sb.append(getString(R.string.label_socks, ip, ShareState.SOCKS_PORT)).append('\n')
        }
        infoText.text = sb.toString().trimEnd()

        val ssid = ShareState.ssid
        val pass = ShareState.password
        if (running && ssid != null && ShareState.mode != ShareState.MODE_LAN) {
            val qrText = "WIFI:T:WPA;S:${escapeWifi(ssid)};P:${escapeWifi(pass ?: "")};;"
            val bmp = qrBitmap(qrText, 512)
            if (bmp != null) {
                qrImage.setImageBitmap(bmp)
                qrImage.visibility = ImageView.VISIBLE
                qrHint.visibility = TextView.VISIBLE
            } else {
                qrImage.visibility = ImageView.GONE
                qrHint.visibility = TextView.GONE
            }
        } else {
            qrImage.setImageDrawable(null)
            qrImage.visibility = ImageView.GONE
            qrHint.visibility = TextView.GONE
        }
    }

    private fun escapeWifi(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap? {
        return try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        } catch (_: Exception) {
            null
        }
    }
}

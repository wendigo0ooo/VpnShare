package com.wendigo0ooo.vpnshare

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var toggleButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var infoText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrHint: TextView
    private lateinit var devicesSummary: TextView
    private lateinit var trafficText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var resetDevices: MaterialButton
    private lateinit var authSwitch: MaterialSwitch
    private lateinit var userField: TextInputEditText
    private lateinit var passField: TextInputEditText
    private lateinit var userLayout: TextInputLayout
    private lateinit var passLayout: TextInputLayout

    private lateinit var prefs: SharedPreferences
    private var lastDeviceSig = ""

    private var prevDown = 0L
    private var prevUp = 0L
    private var prevTime = 0L

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
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)
        infoText = findViewById(R.id.infoText)
        qrImage = findViewById(R.id.qrImage)
        qrHint = findViewById(R.id.qrHint)
        devicesSummary = findViewById(R.id.devicesSummary)
        trafficText = findViewById(R.id.trafficText)
        devicesContainer = findViewById(R.id.devicesContainer)
        resetDevices = findViewById(R.id.resetDevices)
        authSwitch = findViewById(R.id.authSwitch)
        userField = findViewById(R.id.userField)
        passField = findViewById(R.id.passField)
        userLayout = findViewById(R.id.userLayout)
        passLayout = findViewById(R.id.passLayout)

        prefs = getSharedPreferences("vpnshare", MODE_PRIVATE)
        loadAuthPrefs()

        authSwitch.setOnCheckedChangeListener { _, _ -> applyAuth() }
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyAuth()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        userField.addTextChangedListener(watcher)
        passField.addTextChangedListener(watcher)

        resetDevices.setOnClickListener {
            ClientStats.reset()
            lastDeviceSig = ""
            updateUi()
        }

        requestPermissions()

        toggleButton.setOnClickListener {
            if (ShareState.running || ShareState.preparing) {
                startService(
                    Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP)
                )
            } else {
                applyAuth()
                lastDeviceSig = ""
                val mode = when (modeGroup.checkedButtonId) {
                    R.id.modeHotspot -> ShareState.MODE_HOTSPOT
                    R.id.modeLan -> ShareState.MODE_LAN
                    else -> ShareState.MODE_P2P
                }
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ShareService::class.java)
                        .setAction(ShareService.ACTION_START)
                        .putExtra(ShareService.EXTRA_MODE, mode)
                )
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

    private fun loadAuthPrefs() {
        authSwitch.isChecked = prefs.getBoolean(KEY_ENABLED, false)
        userField.setText(prefs.getString(KEY_USER, ""))
        passField.setText(prefs.getString(KEY_PASS, ""))
        updateAuthFields()
        applyAuth()
    }

    private fun updateAuthFields() {
        val on = authSwitch.isChecked
        userLayout.isEnabled = on
        passLayout.isEnabled = on
        userField.isEnabled = on
        passField.isEnabled = on
    }

    private fun applyAuth() {
        updateAuthFields()
        ProxyAuth.enabled = authSwitch.isChecked
        ProxyAuth.username = userField.text?.toString()?.trim() ?: ""
        ProxyAuth.password = passField.text?.toString() ?: ""
        prefs.edit()
            .putBoolean(KEY_ENABLED, ProxyAuth.enabled)
            .putString(KEY_USER, ProxyAuth.username)
            .putString(KEY_PASS, ProxyAuth.password)
            .apply()
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

        when {
            running -> {
                statusText.text = getString(R.string.status_running)
                toggleButton.text = getString(R.string.stop)
            }
            preparing -> {
                statusText.text = getString(R.string.status_starting)
                toggleButton.text = getString(R.string.stop)
            }
            else -> {
                statusText.text = getString(R.string.status_stopped)
                toggleButton.text = getString(R.string.start)
            }
        }

        val enableModes = !running && !preparing
        for (i in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(i).isEnabled = enableModes
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
                hideQr()
            }
        } else {
            hideQr()
        }

        updateTraffic()
        renderDevices()
    }

    private fun updateTraffic() {
        val curDown = ClientStats.totalDown()
        val curUp = ClientStats.totalUp()
        val now = System.currentTimeMillis()
        var downSpeed = 0L
        var upSpeed = 0L
        if (prevTime > 0 && now > prevTime) {
            val dt = (now - prevTime) / 1000.0
            downSpeed = ((curDown - prevDown) / dt).toLong().coerceAtLeast(0)
            upSpeed = ((curUp - prevUp) / dt).toLong().coerceAtLeast(0)
        }
        prevDown = curDown
        prevUp = curUp
        prevTime = now

        trafficText.text = getString(
            R.string.traffic_summary,
            fmtBytes(curDown),
            fmtBytes(curUp),
            fmtBytes(downSpeed),
            fmtBytes(upSpeed)
        )
    }

    private fun hideQr() {
        qrImage.setImageDrawable(null)
        qrImage.visibility = ImageView.GONE
        qrHint.visibility = TextView.GONE
    }

    private fun renderDevices() {
        val list = ClientStats.snapshot()
        val sig = buildString {
            append(ClientStats.activeCount()).append('#')
            for (c in list) {
                append(c.ip).append(c.down.get()).append(c.up.get())
                    .append(c.active.get()).append(c.connections.get())
                    .append(c.type).append(c.hostname)
                    .append(BlockList.isBlocked(c.ip)).append('|')
            }
        }
        if (sig == lastDeviceSig) return
        lastDeviceSig = sig

        devicesSummary.text = getString(
            R.string.devices_summary,
            ClientStats.activeCount(),
            ClientStats.totalCount()
        )
        devicesContainer.removeAllViews()

        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = getString(R.string.devices_empty)
            tv.textSize = 13f
            devicesContainer.addView(tv)
            return
        }
        for (c in list) {
            addDeviceRow(c)
        }
    }

    private fun addDeviceRow(c: ClientInfo) {
        val row = layoutInflater.inflate(R.layout.item_device, devicesContainer, false)
        val text = row.findViewById<TextView>(R.id.rowText)
        val button = row.findViewById<MaterialButton>(R.id.rowButton)

        val blocked = BlockList.isBlocked(c.ip)
        val name = c.hostname ?: c.type ?: "Bilinmiyor"
        var label = getString(
            R.string.conn_info,
            c.ip,
            name,
            fmtBytes(c.down.get()),
            fmtBytes(c.up.get()),
            c.connections.get()
        )
        if (blocked) label += getString(R.string.blocked_suffix)
        text.text = label
        text.setTextColor(
            when {
                blocked -> 0xFFB71C1C.toInt()
                c.active.get() > 0 -> 0xFF1B5E20.toInt()
                else -> 0xFF555555.toInt()
            }
        )
        button.text = getString(if (blocked) R.string.unblock else R.string.block)
        button.setOnClickListener {
            if (BlockList.isBlocked(c.ip)) {
                BlockList.unblock(c.ip)
            } else {
                BlockList.block(c.ip)
            }
            lastDeviceSig = ""
            updateUi()
        }
        devicesContainer.addView(row)
    }

    private fun escapeWifi(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun fmtBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = 1024.0
        return when {
            bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / kb)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (kb * 1024))
            else -> String.format(Locale.US, "%.2f GB", bytes / (kb * 1024 * 1024))
        }
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

    companion object {
        private const val KEY_ENABLED = "auth_enabled"
        private const val KEY_USER = "auth_user"
        private const val KEY_PASS = "auth_pass"
    }
}

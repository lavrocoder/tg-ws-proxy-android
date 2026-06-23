package com.flowseal.tgwsproxy

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.chaquo.python.Python

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE) }
    private val runner by lazy { Python.getInstance().getModule("android_runner") }

    private lateinit var portEdit: EditText
    private lateinit var secretEdit: EditText
    private lateinit var dcEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var logView: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val logTick = object : Runnable {
        override fun run() {
            refreshLog()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        portEdit = findViewById(R.id.port)
        secretEdit = findViewById(R.id.secret)
        dcEdit = findViewById(R.id.dc_ips)
        statusView = findViewById(R.id.status)
        toggleBtn = findViewById(R.id.toggle)
        logView = findViewById(R.id.log)
        logView.movementMethod = ScrollingMovementMethod()

        portEdit.setText(prefs.getInt("port", 1443).toString())
        secretEdit.setText(loadOrCreateSecret())
        dcEdit.setText(loadDcIps())

        toggleBtn.setOnClickListener { if (isRunning()) stopProxy() else startProxy() }
        findViewById<Button>(R.id.generate).setOnClickListener {
            secretEdit.setText(runner.callAttr("gen_secret").toString())
        }
        findViewById<Button>(R.id.open_telegram).setOnClickListener { openInTelegram() }
        findViewById<Button>(R.id.copy_link).setOnClickListener { copyLink() }

        maybeRequestNotificationPermission()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        ui.post(logTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(logTick)
    }

    private fun refreshLog() {
        val text = try {
            runner.callAttr("get_logs").toString()
        } catch (_: Throwable) {
            ""
        }
        if (text != logView.text.toString()) {
            logView.text = text
            // auto-scroll to the newest line
            val scroll = logView.layout?.getLineTop(logView.lineCount) ?: 0
            val pad = scroll - logView.height + logView.paddingTop + logView.paddingBottom
            if (pad > 0) logView.scrollTo(0, pad)
        }
    }

    private fun isRunning(): Boolean =
        try {
            runner.callAttr("is_running").toBoolean()
        } catch (_: Throwable) {
            false
        }

    private fun loadOrCreateSecret(): String {
        var secret = prefs.getString("secret", null)
        if (secret.isNullOrBlank()) {
            secret = runner.callAttr("gen_secret").toString()
            prefs.edit().putString("secret", secret).apply()
        }
        return secret
    }

    private fun loadDcIps(): String {
        val def = "2:149.154.167.220 4:149.154.167.220"
        val saved = prefs.getString("dc_ips", null)
        // Migrate the buggy media-only default shipped in the first build:
        // home DC (2) was missing, so Telegram never used the WS bridge.
        if (saved == null || saved == "4:149.154.167.220") return def
        return saved
    }

    private fun currentPort(): Int =
        portEdit.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 1443

    private fun persist() {
        prefs.edit()
            .putInt("port", currentPort())
            .putString("secret", secretEdit.text.toString().trim())
            .putString("dc_ips", dcEdit.text.toString().trim())
            .apply()
    }

    private fun startProxy() {
        val secret = secretEdit.text.toString().trim()
        if (secret.length != 32 || secret.any { it !in "0123456789abcdefABCDEF" }) {
            toast(getString(R.string.bad_secret))
            return
        }
        persist()
        val intent = Intent(this, ProxyService::class.java)
            .putExtra(ProxyService.EXTRA_PORT, currentPort())
            .putExtra(ProxyService.EXTRA_SECRET, secret)
            .putExtra(ProxyService.EXTRA_DC_IPS, dcEdit.text.toString().trim())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusView.postDelayed({ refreshUi() }, 500)
    }

    private fun stopProxy() {
        startService(
            Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        )
        statusView.postDelayed({ refreshUi() }, 500)
    }

    private fun proxyLink(web: Boolean): String =
        runner.callAttr("make_link", "127.0.0.1", currentPort(),
            secretEdit.text.toString().trim(), web).toString()

    private fun openInTelegram() {
        // Prefer the direct app scheme; fall back to the https://t.me form,
        // which any browser/Telegram will route into the app.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(proxyLink(false))))
            return
        } catch (_: Throwable) {
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(proxyLink(true))))
        } catch (_: Throwable) {
            toast(getString(R.string.no_telegram))
        }
    }

    private fun copyLink() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("tg-ws-proxy", proxyLink(true)))
        toast(getString(R.string.link_copied))
    }

    private fun refreshUi() {
        val running = isRunning()
        toggleBtn.setText(if (running) R.string.stop else R.string.start)
        statusView.setText(if (running) R.string.running else R.string.stopped)
        portEdit.isEnabled = !running
        secretEdit.isEnabled = !running
        dcEdit.isEnabled = !running
        findViewById<Button>(R.id.generate).isEnabled = !running
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

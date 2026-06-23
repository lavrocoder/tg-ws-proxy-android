package com.flowseal.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.chaquo.python.Python

class ProxyService : Service() {

    companion object {
        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_DC_IPS = "dc_ips"
        const val ACTION_STOP = "com.flowseal.tgwsproxy.STOP"

        private const val CHANNEL_ID = "tg_ws_proxy"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 1443) ?: 1443
        val secret = intent?.getStringExtra(EXTRA_SECRET) ?: ""
        val dcIps = intent?.getStringExtra(EXTRA_DC_IPS) ?: ""

        startForegroundCompat(port)
        startProxy(port, secret, dcIps)
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun runner() = Python.getInstance().getModule("android_runner")

    private fun startProxy(port: Int, secret: String, dcIps: String) {
        runner().callAttr(
            "start", port, secret,
            if (dcIps.isBlank()) null else dcIps, true
        )
    }

    private fun stopProxy() {
        try {
            runner().callAttr("stop")
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundCompat(port: Int) {
        createChannel()

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text, port))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(0, getString(R.string.action_stop), stopIntent)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }
}

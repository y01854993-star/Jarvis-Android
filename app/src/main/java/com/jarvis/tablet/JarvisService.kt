package com.jarvis.tablet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class JarvisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var wake: PowerManager.WakeLock? = null
    private var bubble: Bubble? = null

    override fun onCreate() {
        super.onCreate()
        Config.load(this)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis")
            .setContentText("Listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:engine").also { it.acquire() }

        if (engine == null) {
            engine = Engine(this, scope).also { it.start() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureBubble()
        return START_STICKY
    }

    private fun ensureBubble() {
        if (bubble == null && Settings.canDrawOverlays(this)) {
            bubble = Bubble(
                this,
                { engine?.bubbleTap() },
                {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            ).also { it.show() }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bubble?.hide()
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

package com.jarvis.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Brings the floating button back after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(ctx)) return
        try {
            ctx.startForegroundService(Intent(ctx, JarvisService::class.java))
        } catch (_: Exception) {}
    }
}

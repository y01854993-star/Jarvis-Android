package com.jarvis.tablet

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps the latest notifications in memory so Jarvis can answer "ايه اللي جالي؟". */
class JarvisNotifications : NotificationListenerService() {

    companion object {
        private val buf = CopyOnWriteArrayList<String>()
        fun recent(n: Int): List<String> = buf.toList().takeLast(n)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName }
        buf.add("$app: $title - ${text.take(140)}")
        while (buf.size > 30) buf.removeAt(0)
    }
}

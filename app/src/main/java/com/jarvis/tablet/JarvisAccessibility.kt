package com.jarvis.tablet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibility? = null
    }

    @Volatile private var waPendingUntil = 0L
    private var lastAnswerTry = 0L
    private var warnedNoCaller = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Config.load(this)
        Bus.log("Accessibility service ready")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /** Called by Engine. Opens a pre-filled WhatsApp chat; the send button is clicked on the next window event. */
    fun openWhatsApp(url: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        waPendingUntil = System.currentTimeMillis() + 20_000
        true
    } catch (e: Exception) {
        Bus.log("WhatsApp open failed: ${e.javaClass.simpleName}")
        false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return

        if (pkg == "com.whatsapp" && System.currentTimeMillis() < waPendingUntil) {
            if (clickSend(root)) {
                waPendingUntil = 0
                Bus.log("WHATSAPP message sent")
            }
            return
        }

        if (Config.list("botim_package_hints").any { pkg.contains(it, ignoreCase = true) }) {
            tryAnswer(root)
        }
    }

    private fun clickSend(root: AccessibilityNodeInfo): Boolean {
        val byId = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send").firstOrNull()
        val node = byId
            ?: listOf("Send", "إرسال").firstNotNullOfOrNull { root.findAccessibilityNodeInfosByText(it).firstOrNull() }
        return node?.let { clickNode(it) } ?: false
    }

    private fun tryAnswer(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastAnswerTry < 3000) return

        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) return   // already in a call

        val allowed = Config.allowedCaller()
        if (allowed.isBlank()) {
            if (!warnedNoCaller) {
                warnedNoCaller = true
                Bus.log("allowed_caller فاضي - الرد التلقائي معطل")
            }
            return
        }
        if (!collectText(root, 0).contains(allowed, ignoreCase = true)) return
        lastAnswerTry = now

        for (t in Config.list("answer_texts")) {
            val n = root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser }
            if (n != null && clickNode(n)) {
                Bus.log("CALL ANSWERED")
                return
            }
        }

        Config.answerTap()?.let { tap ->
            val dm = resources.displayMetrics
            val path = Path().apply { moveTo((tap[0] * dm.widthPixels).toFloat(), (tap[1] * dm.heightPixels).toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            dispatchGesture(g, null, null)
            Bus.log("CALL ANSWER tap sent")
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun collectText(n: AccessibilityNodeInfo?, depth: Int): String {
        if (n == null || depth > 12) return ""
        val sb = StringBuilder()
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until n.childCount) sb.append(collectText(n.getChild(i), depth + 1))
        return sb.toString()
    }
}

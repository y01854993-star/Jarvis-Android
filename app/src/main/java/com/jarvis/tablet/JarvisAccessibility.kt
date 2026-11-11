package com.jarvis.tablet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
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

    /** Starts any activity (accessibility services are allowed to launch from the background). */
    fun launch(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Bus.log("Launch failed: ${e.javaClass.simpleName}")
        false
    }

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

    // ---------------------------------------------------------- UI automation helpers (used by Engine)
    fun clickFirst(ids: List<String>, texts: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (id in ids) {
            val n = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            if (n != null && clickNode(n)) return true
        }
        for (t in texts) {
            val n = root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser }
            if (n != null && clickNode(n)) return true
        }
        return false
    }

    fun setTextFirst(ids: List<String>, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var node: AccessibilityNodeInfo? = null
        for (id in ids) {
            node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            if (node != null) break
        }
        if (node == null) node = findEditable(root, 0)
        if (node == null) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** clicks the first visible, non-editable node whose text or description satisfies [match] */
    fun clickTextMatching(match: (String) -> Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findText(root, match, 0) ?: return false
        return clickNode(n)
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    private fun findEditable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 14) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun findText(n: AccessibilityNodeInfo?, match: (String) -> Boolean, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 16) return null
        if (!n.isEditable && n.isVisibleToUser) {
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            if ((t.isNotEmpty() && match(t)) || (d.isNotEmpty() && match(d))) return n
        }
        for (i in 0 until n.childCount) {
            val r = findText(n.getChild(i), match, depth + 1)
            if (r != null) return r
        }
        return null
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

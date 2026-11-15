package com.jarvis.tablet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

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
        Keys.init(this)
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
        if (recording) record(event)
        val root = rootInActiveWindow ?: return

        if (pkg == "com.whatsapp" && System.currentTimeMillis() < waPendingUntil) {
            if (clickSend(root)) {
                waPendingUntil = 0
                Bus.log("WHATSAPP message sent")
            }
            return
        }

        if (Config.list("botim_package_hints").any { pkg.contains(it, ignoreCase = true) } ||
            (Keys.studyActive() && Config.list("study_voip_packages").any { pkg.contains(it, ignoreCase = true) })) {
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

        val study = Keys.studyActive()
        val allowed = Config.allowedCaller()
        if (study) {
            if (!looksLikeIncomingCall(root)) return
        } else {
            if (allowed.isBlank()) {
                if (!warnedNoCaller) {
                    warnedNoCaller = true
                    Bus.log("allowed_caller فاضي - الرد التلقائي معطل")
                }
                return
            }
            if (!collectText(root, 0).contains(allowed, ignoreCase = true)) return
        }
        lastAnswerTry = now

        for (t in Config.list("answer_texts")) {
            val n = root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser }
            if (n != null && clickNode(n)) {
                Bus.log("CALL ANSWERED")
                if (study) Bus.onVoipAnswered?.invoke()
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

    // ---------------------------------------------------------- screen reading + gestures (agent)
    /** numbered, compact description of what is on screen + the matching nodes */
    fun dumpScreen(maxNodes: Int = 120): Pair<String, List<AccessibilityNodeInfo>> {
        val root = rootInActiveWindow ?: return "" to emptyList()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        sb.append("app: ").append(root.packageName).append('\n')
        walk(root, 0, nodes, sb, maxNodes)
        return sb.toString() to nodes
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, out: MutableList<AccessibilityNodeInfo>, sb: StringBuilder, max: Int) {
        if (n == null || depth > 25 || out.size >= max) return
        if (n.isVisibleToUser) {
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val interesting = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || t.isNotEmpty() || d.isNotEmpty()
            if (interesting) {
                val idx = out.size
                out.add(n)
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("click")
                if (n.isEditable) flags.add("edit")
                if (n.isScrollable) flags.add("scroll")
                if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
                if (!n.isEnabled) flags.add("disabled")
                val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
                val label = (if (t.isNotEmpty()) t else d).replace("\n", " ").take(70)
                val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
                sb.append('[').append(idx).append("] ").append(cls)
                if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
                if (id.isNotEmpty()) sb.append(" #").append(id)
                if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(')')
                sb.append('\n')
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, out, sb, max)
    }

    fun tapNode(n: AccessibilityNodeInfo): Boolean {
        if (clickNode(n)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun longClickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isLongClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            cur = cur.parent
            depth++
        }
        return false
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null
        )
    }

    fun swipe(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (direction) {
            "up" -> { p.moveTo(w * 0.5f, h * 0.75f); p.lineTo(w * 0.5f, h * 0.25f) }
            "down" -> { p.moveTo(w * 0.5f, h * 0.25f); p.lineTo(w * 0.5f, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.8f, h * 0.5f); p.lineTo(w * 0.2f, h * 0.5f) }
            "right" -> { p.moveTo(w * 0.2f, h * 0.5f); p.lineTo(w * 0.8f, h * 0.5f) }
            else -> return false
        }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null
        )
    }

    fun typeInto(n: AccessibilityNodeInfo?, text: String, enter: Boolean): Boolean {
        val target = n ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && enter && Build.VERSION.SDK_INT >= 30) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    fun global(name: String): Boolean = when (name) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        "screenshot" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false
        else -> false
    }

    /** takes a screenshot (Android 11+) and returns it as a downscaled base64 JPEG */
    fun takeScreenshotB64(maxW: Int, onDone: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { onDone(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = r.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hw, r.colorSpace)
                        hw.close()
                        if (bmp == null) { onDone(null); return }
                        val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        val scale = minOf(1f, maxW.toFloat() / soft.width)
                        val out = if (scale < 1f)
                            Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true)
                        else soft
                        val bos = ByteArrayOutputStream()
                        out.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                        onDone(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                    } catch (e: Exception) { onDone(null) }
                }
                override fun onFailure(errorCode: Int) { onDone(null) }
            })
        } catch (e: Exception) { onDone(null) }
    }

    // ---------------------------------------------------------- learn by demonstration
    @Volatile var recording = false
    private val recSteps = ArrayList<JSONObject>()
    private var recLastClickAt = 0L
    private var recLastPkg = ""

    fun startRecording() {
        synchronized(recSteps) { recSteps.clear() }
        recLastPkg = ""
        recording = true
    }

    fun stopRecording(): List<JSONObject> {
        recording = false
        synchronized(recSteps) { return ArrayList(recSteps) }
    }

    private fun record(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg.contains("systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != recLastPkg) {
                    recLastPkg = pkg
                    synchronized(recSteps) { recSteps.add(JSONObject().put("t", "open").put("pkg", pkg)) }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val now = System.currentTimeMillis()
                if (now - recLastClickAt < 350) return
                recLastClickAt = now
                val src = e.source
                if (e.isPassword || src?.isPassword == true) return
                val label = e.text.firstOrNull { !it.isNullOrBlank() }?.toString()
                    ?: src?.text?.toString() ?: src?.contentDescription?.toString()
                    ?: e.contentDescription?.toString() ?: ""
                val step = JSONObject()
                    .put("t", if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) "long_click" else "click")
                    .put("l", label.take(60)).put("id", src?.viewIdResourceName ?: "").put("pkg", pkg)
                synchronized(recSteps) { recSteps.add(step) }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (e.isPassword) return
                val txt = e.text.firstOrNull()?.toString() ?: ""
                val id = e.source?.viewIdResourceName ?: ""
                synchronized(recSteps) {
                    val last = recSteps.lastOrNull()
                    if (last != null && last.optString("t") == "type" && last.optString("id") == id) last.put("txt", txt.take(200))
                    else recSteps.add(JSONObject().put("t", "type").put("txt", txt.take(200)).put("id", id).put("pkg", pkg))
                }
            }
        }
    }

    /** replay: click the element with this id whose text contains [label] (or just the label) */
    fun findAndClick(viewId: String, label: String, long: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val l = label.trim()
        val byId = if (viewId.isNotBlank()) root.findAccessibilityNodeInfosByViewId(viewId) else emptyList()
        val pick: AccessibilityNodeInfo? =
            if (l.isNotEmpty()) byId.firstOrNull { collectText(it, 0).contains(l, true) }
                ?: root.findAccessibilityNodeInfosByText(l).firstOrNull { it.isVisibleToUser }
            else byId.firstOrNull { it.isVisibleToUser }
        return pick?.let { if (long) longClickNode(it) else tapNode(it) } ?: false
    }

    fun typeById(viewId: String, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = if (viewId.isNotBlank()) root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull() else null
        return typeInto(n, text, false)
    }

    /** an incoming VoIP call screen offers both answer and decline */
    private fun looksLikeIncomingCall(root: AccessibilityNodeInfo): Boolean {
        val t = collectText(root, 0).lowercase()
        val answer = listOf("answer", "accept", "رد", "الرد", "قبول").any { t.contains(it) }
        val decline = listOf("decline", "reject", "رفض").any { t.contains(it) }
        return answer && decline
    }

    /** shares a file to WhatsApp and lets the send-button clicker finish the job */
    fun shareToWhatsApp(i: Intent): Boolean = try {
        startActivity(i)
        waPendingUntil = System.currentTimeMillis() + 25_000
        true
    } catch (e: Exception) {
        Bus.log("share failed: ${e.javaClass.simpleName}")
        false
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

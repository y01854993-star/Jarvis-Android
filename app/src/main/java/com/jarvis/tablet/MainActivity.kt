package com.jarvis.tablet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)
        Keys.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(object {
            @JavascriptInterface fun sendText(t: String) { Bus.onText?.invoke(t) }
            @JavascriptInterface fun setLang(l: String) { Keys.setLang(l) }
            @JavascriptInterface fun fixStats(): String = try { GitHub.fixStats().toString() } catch (_: Exception) { "{}" }
            @JavascriptInterface fun openUrl(u: String) {
                if (u.startsWith("https://github.com/")) {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            }
            @JavascriptInterface fun reportProblem(s: String) { Problems.add(s.take(300)) }
            @JavascriptInterface fun skillStats(): String = try { GitHub.skillSummary() ?: "{}" } catch (_: Exception) { "{}" }
            @JavascriptInterface fun skillAction(issue: Int, cmd: String): String {
                if (issue <= 0 || cmd !in setOf("/approve", "/reject", "/test", "/info")) return "refused"
                val body = if (cmd == "/info") "/info please share more details and the main risks" else cmd
                return try { if (GitHub.commentIssue(issue, body)) "ok" else "failed" } catch (_: Exception) { "failed" }
            }
            @JavascriptInterface fun localProblems(): String = try {
                val arr = org.json.JSONArray()
                Problems.lines().takeLast(6).reversed().forEach { arr.put(Redact.mask(it).take(300)) }
                arr.toString()
            } catch (_: Exception) { "[]" }
            private fun reply(id: String, result: String) {
                runOnUiThread { web.evaluateJavascript("window.onBridgeResult&&window.onBridgeResult(${JSONObject.quote(id)},${JSONObject.quote(result)})", null) }
            }
            @JavascriptInterface fun diagnoseProblemAsync(id: String, text: String) {
                Thread { reply(id, Bus.onDiagnose?.invoke(text) ?: "{\"error\":\"engine not ready\"}") }.start()
            }
            @JavascriptInterface fun reportProblemToGitHub(id: String, text: String) {
                Thread {
                    val ok = try { GitHub.createIssue("Jarvis problem reported from the HUD", "Reported from the HUD.\n\n```\n" + Redact.mask(text).take(1500) + "\n```\n", listOf("jarvis-problem")) } catch (_: Exception) { false }
                    reply(id, if (ok) "ok" else "failed")
                }.start()
            }
            @JavascriptInterface fun askEngineer(id: String, issue: Int) {
                Thread {
                    val ok = try { issue > 0 && GitHub.commentIssue(issue, "/fix") } catch (_: Exception) { false }
                    reply(id, if (ok) "ok" else "failed")
                }.start()
            }
            @JavascriptInterface fun routerStats(): String = try { Usage.dashboard().toString() } catch (_: Exception) { "{}" }
            @JavascriptInterface fun toggleListen(lang: String) { Bus.onToggle?.invoke(lang) }
            @JavascriptInterface fun testLocal() { Bus.onTestLocal?.invoke() }
            @JavascriptInterface fun testVoice() { Bus.onTestVoice?.invoke() }
            @JavascriptInterface fun testHealth() { Bus.onHealth?.invoke() }
            @JavascriptInterface fun unlock() { runOnUiThread { requestUnlock() } }
            @JavascriptInterface fun wipeAll() {
                try {
                    Brain.init(this@MainActivity); Brain.wipe()
                    Keys.init(this@MainActivity); Keys.wipeAll()
                    for (n in listOf("jarvis_autoreply", "jarvis_health", "jarvis_memory")) { try { deleteSharedPreferences(n) } catch (_: Exception) {} }
                    Bus.say("j", "🧹 اتمسحت الذاكرة والمفاتيح والإعدادات")
                } catch (_: Exception) {}
            }
            @JavascriptInterface fun toggleStudy() { Bus.onToggleStudy?.invoke() }
            @JavascriptInterface fun setKeys(json: String) {
                try { Keys.save(JSONObject(json)) } catch (_: Exception) {}
            }
        }, "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val js = assets.open("hud_bridge.js").bufferedReader().readText()
                view.evaluateJavascript(js, null)
                Bus.lastStatus?.let { push(it) }
                try {
                    Brain.init(this@MainActivity)
                    for ((u, r) in Brain.recentTurns(10)) {
                        push(JSONObject().put("type", "say").put("c", "u").put("t", u).toString())
                        push(JSONObject().put("type", "say").put("c", "j").put("t", r).toString())
                    }
                } catch (_: Exception) {}
            }
        }
        web.loadUrl("file:///android_asset/jarvis-hud.html")
        Bus.sink = { json -> push(json) }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startEngine()
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startEngine()
        else Toast.makeText(this, "لازم إذن المايك", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        val a11y = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!a11y.contains(packageName) && !askedA11y) {
            askedA11y = true
            Toast.makeText(this, "فعّل خدمة Jarvis من إعدادات إمكانية الوصول", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true
            Toast.makeText(this, "فعّل \"العرض فوق التطبيقات\" لـ Jarvis عشان الزر العائم", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (!nl.contains(packageName) && !askedNotif) {
            askedNotif = true
            Toast.makeText(this, "فعّل وصول Jarvis للإشعارات", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        if (!Settings.System.canWrite(this) && !askedWrite) {
            askedWrite = true
            Toast.makeText(this, "اسمح لـ Jarvis بتعديل إعدادات النظام", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !askedBattery) {
            askedBattery = true
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        if (Settings.canDrawOverlays(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startEngine()
    }

    companion object {
        private var askedA11y = false
        private var askedOverlay = false
        private var askedNotif = false
        private var askedWrite = false
        private var askedBattery = false
    }

    private fun requestUnlock() {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isDeviceSecure) {
            Keys.unlockFor(10 * 60_000L)
            Bus.say("j", "مفيش قفل شاشة على الجهاز، فتحت الميزات لمدة 10 دقايق")
            return
        }
        @Suppress("DEPRECATION")
        val i = km.createConfirmDeviceCredentialIntent("Jarvis", "افتح الميزات المقفولة")
        if (i != null) startActivityForResult(i, 77)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            Keys.unlockFor(10 * 60_000L)
            Bus.say("j", "🔓 اتفتحت الميزات لمدة 10 دقايق")
        }
    }

    override fun onDestroy() {
        Bus.sink = null
        super.onDestroy()
    }

    private fun startEngine() {
        startForegroundService(Intent(this, JarvisService::class.java))
    }

    private fun push(json: String) {
        runOnUiThread { web.evaluateJavascript("window.jarvisEvent&&window.jarvisEvent($json)", null) }
    }
}

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
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
        web.settings.allowFileAccess = true // required only for the bundled HUD assets
        web.settings.allowFileAccessFromFileURLs = false
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.setSupportMultipleWindows(false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.addJavascriptInterface(object {
            @JavascriptInterface fun sendText(t: String) { Bus.onText?.invoke(t) }
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
            @JavascriptInterface fun setKeys(json: String): Boolean {
                return try { Keys.save(JSONObject(json)) } catch (_: Exception) { false }
            }
            @JavascriptInterface fun clearKeys() { Keys.clearSecrets() }
            @JavascriptInterface fun hasSavedKeys(): Boolean { return try { Keys.hasCloud() } catch (_: Exception) { false } }
            @JavascriptInterface fun taskHistory(): String {
                return try { Brain.init(this@MainActivity); Brain.recentTasksJson(50) } catch (_: Exception) { "[]" }
            }
        }, "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val isBundledAsset = uri.scheme == "file" && uri.host.isNullOrEmpty() &&
                    uri.path?.startsWith("/android_asset/") == true
                return !isBundledAsset
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!url.startsWith("file:///android_asset/")) return
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
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
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

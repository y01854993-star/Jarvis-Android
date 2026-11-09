package com.jarvis.tablet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val js = assets.open("hud_bridge.js").bufferedReader().readText()
                view.evaluateJavascript(js, null)
                Bus.lastStatus?.let { push(it) }
            }
        }
        web.loadUrl("file:///android_asset/jarvis-hud.html")
        Bus.sink = { json -> push(json) }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startEngine()
        else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startEngine()
        else Toast.makeText(this, "لازم إذن المايك", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!enabled.contains(packageName)) {
            Toast.makeText(this, "فعّل خدمة Jarvis من إعدادات إمكانية الوصول", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

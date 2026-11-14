package com.jarvis.tablet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Keys {
    private lateinit var sp: SharedPreferences
    @Volatile var version = 0

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    }

    val groq: String get() = sp.getString("groq", "") ?: ""
    val openai: String get() = sp.getString("openai", "") ?: ""
    val gemini: String get() = (sp.getString("gemini", "") ?: "").ifBlank { BuildConfig.GEMINI_KEY }
    val name: String get() = (sp.getString("name", "") ?: "").trim().let { if (it.isBlank() || it == "يا فندم") "Boss" else it }

    val fish: String get() = sp.getString("fish", "") ?: ""
    val fishVoice: String get() {
        val raw = sp.getString("fishvoice", "") ?: ""
        return Regex("[0-9a-fA-F]{32}").find(raw)?.value ?: raw.trim()
    }

    val ds: String get() = sp.getString("ds", "") ?: ""
    val kimi: String get() = sp.getString("kimi", "") ?: ""
    val ghToken: String get() = sp.getString("ghtoken", "") ?: ""
    val ghRepo: String get() = sp.getString("ghrepo", "") ?: ""

    val localOn: Boolean get() = sp.getBoolean("localon", false)
    val localOnly: Boolean get() = sp.getBoolean("localonly", true)
    val sttDevice: Boolean get() = sp.getBoolean("sttdev", false)
    val localUrl: String get() = (sp.getString("localurl", "") ?: "").ifBlank { "http://127.0.0.1:8080/v1" }

    fun hasCloud() = groq.isNotBlank() || openai.isNotBlank() || gemini.isNotBlank() || ds.isNotBlank() || kimi.isNotBlank()

    fun hasLlm() = groq.isNotBlank() || openai.isNotBlank() || gemini.isNotBlank() || ds.isNotBlank() || kimi.isNotBlank() || localOn

    fun save(json: JSONObject) {
        version++
        sp.edit()
            .putString("openai", json.optString("gpt").trim())
            .putString("groq", json.optString("groq").trim())
            .putString("name", json.optString("name").trim())
            .putString("gemini", json.optString("gem").trim())
            .putString("fish", json.optString("fish").trim())
            .putString("fishvoice", json.optString("fishv").trim())
            .putBoolean("localon", json.optBoolean("localon", false))
            .putString("localurl", json.optString("localurl").trim())
            .putBoolean("localonly", json.optBoolean("localonly", true))
            .putBoolean("sttdev", json.optBoolean("sttdev", false))
            .putString("ds", json.optString("ds").trim())
            .putString("kimi", json.optString("kimi").trim())
            .putString("ghtoken", json.optString("gh").trim())
            .putString("ghrepo", json.optString("ghrepo").trim())
            .apply()
    }

    fun memory(): List<String> = try {
        val a = JSONArray(sp.getString("memory", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    fun addMemory(fact: String) {
        val l = memory().toMutableList()
        l.add(fact.trim())
        while (l.size > 40) l.removeAt(0)
        sp.edit().putString("memory", JSONArray(l).toString()).apply()
    }

    fun clearMemory() { sp.edit().remove("memory").apply() }
}

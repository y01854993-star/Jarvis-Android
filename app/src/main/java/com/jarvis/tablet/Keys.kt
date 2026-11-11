package com.jarvis.tablet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Keys {
    private lateinit var sp: SharedPreferences

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    }

    val groq: String get() = sp.getString("groq", "") ?: ""
    val openai: String get() = sp.getString("openai", "") ?: ""
    val gemini: String get() = (sp.getString("gemini", "") ?: "").ifBlank { BuildConfig.GEMINI_KEY }
    val name: String get() = (sp.getString("name", "") ?: "").trim().let { if (it.isBlank() || it == "يا فندم") "Boss" else it }

    fun hasLlm() = groq.isNotBlank() || openai.isNotBlank() || gemini.isNotBlank()

    fun save(json: JSONObject) {
        sp.edit()
            .putString("openai", json.optString("gpt").trim())
            .putString("groq", json.optString("groq").trim())
            .putString("name", json.optString("name").trim())
            .putString("gemini", json.optString("gem").trim())
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

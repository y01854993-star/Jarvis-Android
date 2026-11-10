package com.jarvis.tablet

import android.content.Context
import android.content.SharedPreferences
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
    val name: String get() = (sp.getString("name", "") ?: "").ifBlank { "يا فندم" }

    fun hasLlm() = groq.isNotBlank() || openai.isNotBlank() || gemini.isNotBlank()

    fun save(json: JSONObject) {
        sp.edit()
            .putString("openai", json.optString("gpt").trim())
            .putString("groq", json.optString("groq").trim())
            .putString("name", json.optString("name").trim())
            .apply()
    }
}

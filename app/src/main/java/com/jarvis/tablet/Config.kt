package com.jarvis.tablet

import android.content.Context
import org.json.JSONObject

object Config {
    private var json: JSONObject = JSONObject()
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        json = JSONObject(ctx.assets.open("config.json").bufferedReader().readText())
        loaded = true
    }

    val apiKey: String get() = BuildConfig.GEMINI_KEY
    fun model() = json.optString("model", "gemini-2.5-flash")
    fun alwaysListen() = json.optBoolean("always_listen", false)
    fun chunkSeconds() = json.optInt("chunk_seconds", 6)
    fun silenceRms() = json.optDouble("silence_rms", 300.0)
    fun allowedCaller() = json.optString("allowed_caller", "")
    fun pcMac() = json.optString("pc_mac", "")
    fun broadcastIp() = json.optString("broadcast_ip", "192.168.1.255")
    fun contacts(): JSONObject = json.optJSONObject("contacts") ?: JSONObject()

    fun audioSource(): Int = when (json.optString("audio_source", "MIC")) {
        "VOICE_RECOGNITION" -> 6
        "VOICE_COMMUNICATION" -> 7
        "UNPROCESSED" -> 9
        else -> 1
    }

    fun list(key: String): List<String> {
        val a = json.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    fun answerTap(): DoubleArray? {
        val a = json.optJSONArray("answer_tap") ?: return null
        if (a.length() < 2) return null
        return doubleArrayOf(a.getDouble(0), a.getDouble(1))
    }
}

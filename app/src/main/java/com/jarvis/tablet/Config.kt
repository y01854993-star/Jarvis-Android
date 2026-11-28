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

    val apiKey: String get() = Keys.gemini
    fun model() = json.optString("model", "gemini-2.5-flash")
    fun alwaysListen() = json.optBoolean("always_listen", false)
    fun chunkSeconds() = json.optInt("chunk_seconds", 6)
    fun silenceRms() = json.optDouble("silence_rms", 300.0)
    fun allowedCaller() = json.optString("allowed_caller", "")
    fun brain() = json.optString("brain", "auto")
    fun ttsProvider() = json.optString("tts_provider", "auto")
    fun ttsVoice() = json.optString("tts_voice", "onyx")
    fun groqArabicVoice() = json.optString("groq_arabic_voice", "fahad")
    fun robotFx() = json.optDouble("robot_fx", 0.0)
    fun wakePhrase() = json.optString("wake_phrase", "Yeah, Boss?")
    fun followupPhrase() = json.optString("followup_phrase", "Anything else?")
    fun byePhrase() = json.optString("bye_phrase", "Alright, I'm here if you need me.")
    fun confirmMessages() = json.optBoolean("confirm_messages", true)
    fun geminiVoice() = json.optString("gemini_voice", "Charon")
    fun fishModel() = json.optString("fish_model", "s2-pro")
    fun fishFor() = json.optString("fish_for", "all")
    fun agentVision() = json.optBoolean("agent_vision", true)
    fun geminiDeepModel() = json.optString("gemini_deep_model", "gemini-2.5-pro")
    fun localModel() = json.optString("local_model", "qwen2.5-1.5b-instruct")
    fun localMaxTokens() = json.optInt("local_max_tokens", 400)
    fun sttLang() = json.optString("stt_lang", "ar-EG")
    fun assistantName() = json.optString("assistant_name", "جرجس")
    fun ownerName() = json.optString("owner_name", "ياسين الزكي")
    fun studyAnswer() = json.optBoolean("study_answer", true)
    fun studySms() = json.optBoolean("study_sms", true)
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

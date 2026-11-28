package com.jarvis.tablet

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject

/** Settings + secrets. API keys are stored encrypted (Android Keystore) and decrypted on demand. */
object Keys {
    private lateinit var sp: SharedPreferences
    private val cache = HashMap<String, String>()
    @Volatile var version = 0

    private val secretFields = linkedMapOf(
        "gpt" to "openai", "groq" to "groq", "gem" to "gemini", "fish" to "fish",
        "ds" to "ds", "kimi" to "kimi", "gh" to "ghtoken", "freekey" to "freekey"
    )

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun sget(k: String): String {
        cache[k]?.let { return it }
        val v = sp.getString(k, "") ?: ""
        val plain = if (v.startsWith("ENC:")) {
            try { String(Vault.decrypt(Base64.decode(v.substring(4), Base64.NO_WRAP)), Charsets.UTF_8) } catch (_: Exception) { "" }
        } else v // legacy plaintext entries are read for migration, but never written again
        cache[k] = plain
        return plain
    }

    // ---- secrets
    val groq: String get() = sget("groq")
    val openai: String get() = sget("openai")
    val gemini: String get() = sget("gemini")
    val fish: String get() = sget("fish")
    val ds: String get() = sget("ds")
    val kimi: String get() = sget("kimi")
    val ghToken: String get() = sget("ghtoken")
    val freeKey: String get() = sget("freekey")

    // ---- plain settings
    val name: String get() = (sp.getString("name", "") ?: "").trim().let { if (it.isBlank() || it == "يا فندم") "Boss" else it }
    val ghRepo: String get() = sp.getString("ghrepo", "") ?: ""
    val freeUrl: String get() = (sp.getString("freeurl", "") ?: "").ifBlank { "http://127.0.0.1:3001/v1" }
    val fishVoice: String get() {
        val raw = sp.getString("fishvoice", "") ?: ""
        return Regex("[0-9a-fA-F]{32}").find(raw)?.value ?: raw.trim()
    }
    val localOn: Boolean get() = sp.getBoolean("localon", false)
    val localOnly: Boolean get() = sp.getBoolean("localonly", true)
    val sttDevice: Boolean get() = sp.getBoolean("sttdev", false)
    val localUrl: String get() = (sp.getString("localurl", "") ?: "").ifBlank { "http://127.0.0.1:8080/v1" }
    val wakeOn: Boolean get() = sp.getBoolean("wake", false)
    val perf: String get() = sp.getString("perf", "balanced") ?: "balanced"
    val ttsSpeed: Float get() = sp.getFloat("speed", 1.0f)
    val ttsPitch: Float get() = sp.getFloat("pitch", 1.0f)
    val patienceMs: Int get() = sp.getInt("patience", 1200)
    val lockOn: Boolean get() = sp.getBoolean("lock", false)
    val tone: String get() = sp.getString("tone", "friend") ?: "friend"

    fun hasCloud() = groq.isNotBlank() || openai.isNotBlank() || gemini.isNotBlank() || ds.isNotBlank() || kimi.isNotBlank() || freeKey.isNotBlank()
    fun hasLlm() = hasCloud() || localOn

    fun studyActive(): Boolean =
        ::sp.isInitialized && sp.getBoolean("study", false) && System.currentTimeMillis() < sp.getLong("studyuntil", 0L)
    fun setStudy(on: Boolean, until: Long) { sp.edit().putBoolean("study", on).putLong("studyuntil", until).apply() }
    fun setTone(t: String) { sp.edit().putString("tone", t).apply() }

    fun isUnlocked(): Boolean = !lockOn || System.currentTimeMillis() < sp.getLong("unlockuntil", 0L)
    fun unlockFor(ms: Long) { sp.edit().putLong("unlockuntil", System.currentTimeMillis() + ms).apply() }

    fun setVoice(speed: Double?, pitch: Double?, patience: Int?) {
        val e = sp.edit()
        if (speed != null) e.putFloat("speed", speed.toFloat().coerceIn(0.6f, 1.6f))
        if (pitch != null) e.putFloat("pitch", pitch.toFloat().coerceIn(0.6f, 1.6f))
        if (patience != null) e.putInt("patience", patience.coerceIn(500, 3000))
        e.apply()
    }

    @Synchronized
    fun clearSecrets() {
        cache.clear()
        val e = sp.edit()
        secretFields.values.forEach { e.remove(it) }
        e.apply()
        version++
    }

    @Synchronized
    fun wipeAll() {
        cache.clear()
        sp.edit().clear().apply()
    }

    /** Saves settings. Blank secret fields mean "leave unchanged"; key removal is explicit. */
    @Synchronized
    fun save(json: JSONObject): Boolean {
        val encrypted = LinkedHashMap<String, String>()
        try {
            for ((field, prefKey) in secretFields) {
                val value = json.optString(field).trim()
                if (value.isNotEmpty()) {
                    encrypted[prefKey] = "ENC:" + Base64.encodeToString(
                        Vault.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
                    )
                }
            }
        } catch (_: Exception) {
            return false // never fall back to plaintext
        }

        val e = sp.edit()
        for ((key, value) in encrypted) {
            e.putString(key, value)
            cache[key] = json.optString(secretFields.entries.first { it.value == key }.key).trim()
        }
        e.putString("freeurl", json.optString("freeurl").trim())
        e.putString("name", json.optString("name").trim())
        e.putString("fishvoice", json.optString("fishv").trim())
        e.putString("ghrepo", json.optString("ghrepo").trim())
        e.putBoolean("localon", json.optBoolean("localon", false))
        e.putString("localurl", json.optString("localurl").trim())
        e.putBoolean("localonly", json.optBoolean("localonly", true))
        e.putBoolean("sttdev", json.optBoolean("sttdev", false))
        e.putBoolean("wake", json.optBoolean("wake", false))
        e.putString("perf", json.optString("perf", "balanced").ifBlank { "balanced" })
        e.putBoolean("lock", json.optBoolean("lock", false))
        e.putFloat("speed", json.optDouble("speed", 1.0).toFloat().coerceIn(0.6f, 1.6f))
        e.putFloat("pitch", json.optDouble("pitch", 1.0).toFloat().coerceIn(0.6f, 1.6f))
        e.putInt("patience", json.optInt("patience", 1200).coerceIn(500, 3000))
        e.apply()
        version++
        return true
    }
}

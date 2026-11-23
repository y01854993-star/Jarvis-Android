package com.jarvis.tablet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Per-provider usage statistics for the model router (calls, failures, latency, last error). Stored locally, no content. */
object Usage {
    private lateinit var sp: SharedPreferences

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("jarvis_usage", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun record(p: String, ok: Boolean, ms: Long, err: String? = null) {
        if (!::sp.isInitialized) return
        val e = sp.edit()
        e.putInt("calls_$p", sp.getInt("calls_$p", 0) + 1)
        e.putLong("last_$p", System.currentTimeMillis())
        if (ok) {
            e.putInt("ok_$p", sp.getInt("ok_$p", 0) + 1).putLong("ms_$p", sp.getLong("ms_$p", 0L) + ms)
        } else {
            e.putInt("fail_$p", sp.getInt("fail_$p", 0) + 1).putString("err_$p", (err ?: "").take(120))
        }
        e.apply()
    }

    fun stat(p: String): Pair<Int, Int> =
        if (::sp.isInitialized) sp.getInt("calls_$p", 0) to sp.getInt("fail_$p", 0) else 0 to 0

    fun dashboard(): JSONObject {
        val rows = JSONArray()
        val names = Router.PROVIDERS + "local"
        for (p in names) {
            val has = when (p) {
                "gemini" -> Keys.gemini.isNotBlank(); "groq" -> Keys.groq.isNotBlank(); "openai" -> Keys.openai.isNotBlank()
                "deepseek" -> Keys.ds.isNotBlank(); "kimi" -> Keys.kimi.isNotBlank(); else -> Keys.localOn
            }
            val ok = if (::sp.isInitialized) sp.getInt("ok_$p", 0) else 0
            rows.put(JSONObject().put("name", p).put("hasKey", has)
                .put("calls", stat(p).first).put("fail", stat(p).second)
                .put("avgMs", if (ok > 0) sp.getLong("ms_$p", 0L) / ok else 0L)
                .put("lastErr", if (::sp.isInitialized) sp.getString("err_$p", "") else "")
                .put("cooldownMin", Health.remainingMs(p) / 60000).put("why", Health.why(p)))
        }
        val prefs = JSONObject()
        for (t in listOf("chat", "coding", "reasoning", "translation", "summarization", "planning", "android_control", "vision")) {
            Keys.routePref(t)?.let { prefs.put(t, it) }
        }
        return JSONObject().put("mode", Config.brain()).put("providers", rows).put("prefs", prefs)
    }
}

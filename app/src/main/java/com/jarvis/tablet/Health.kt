package com.jarvis.tablet

import android.content.Context
import android.content.SharedPreferences

/** Per-provider cooldown: hard failures (bad key, no credit) switch a provider off for 24h, silently. */
object Health {
    private lateinit var sp: SharedPreferences
    private const val DAY = 24L * 3600 * 1000

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("jarvis_health", Context.MODE_PRIVATE)
    }

    private fun ready() = ::sp.isInitialized

    fun isDisabled(p: String): Boolean = ready() && System.currentTimeMillis() < sp.getLong("until_$p", 0L)

    fun markOk(p: String) {
        if (!ready()) return
        sp.edit().remove("until_$p").remove("why_$p").putInt("fails_$p", 0).apply()
    }

    fun markFail(p: String, e: Exception) {
        if (!ready() || p == "local" || e !is HttpException) return
        val credit = e.body.contains("credit", true) || e.body.contains("quota", true) ||
            e.body.contains("billing", true) || e.body.contains("insufficient", true)
        val hard = e.code == 401 || e.code == 402 || e.code == 403 || (e.code == 429 && credit)
        val n = sp.getInt("fails_$p", 0) + 1
        val why = when {
            e.code == 429 && credit -> "الرصيد خلص"
            e.code == 429 -> "حد الاستخدام"
            e.code == 401 || e.code == 403 -> "المفتاح غلط"
            e.code == 402 -> "محتاج دفع"
            else -> "خطأ ${e.code}"
        }
        val ed = sp.edit().putInt("fails_$p", n).putString("why_$p", why)
        // hard failures (bad key, no credit) = a day; plain rate limit = 3 minutes so the next model takes over right away
        val cool = when {
            hard -> DAY
            e.code == 429 -> 3L * 60 * 1000
            n >= 3 -> 15L * 60 * 1000
            else -> 0L
        }
        if (cool > 0) ed.putLong("until_$p", System.currentTimeMillis() + cool)
        ed.apply()
    }
}

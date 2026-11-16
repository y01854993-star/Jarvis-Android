package com.jarvis.tablet

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class WaMsg(val sender: String, val text: String, val group: Boolean, val action: Notification.Action)

/** Rules for replying to WhatsApp messages on the user's behalf. Off by default; allow-list based. */
object AutoReply {
    private lateinit var sp: SharedPreferences
    private val last = HashMap<String, Long>()
    private var dayKey = ""
    private var dayCount = 0

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("jarvis_autoreply", Context.MODE_PRIVATE)
    }

    fun enabled(): Boolean = ::sp.isInitialized && sp.getBoolean("on", false)
    fun disclose(): Boolean = !::sp.isInitialized || sp.getBoolean("disclose", true)
    fun style(): String = if (::sp.isInitialized) (sp.getString("style", "") ?: "") else ""

    private fun toList(o: Any?): List<String> = when (o) {
        is JSONArray -> (0 until o.length()).map { o.optString(it) }
        is String -> o.split("|", ",", "،").map { it.trim() }
        else -> emptyList()
    }.filter { it.isNotBlank() }

    fun configure(j: JSONObject) {
        val e = sp.edit()
        if (j.has("on")) e.putBoolean("on", j.optBoolean("on"))
        if (j.has("everyone")) e.putBoolean("everyone", j.optBoolean("everyone"))
        if (j.has("groups")) e.putBoolean("groups", j.optBoolean("groups"))
        if (j.has("only_study")) e.putBoolean("only_study", j.optBoolean("only_study"))
        if (j.has("disclose")) e.putBoolean("disclose", j.optBoolean("disclose"))
        if (j.has("from")) e.putInt("from", j.optInt("from", -1))
        if (j.has("to")) e.putInt("to", j.optInt("to", -1))
        if (j.has("style")) e.putString("style", j.optString("style"))
        if (j.has("contacts")) e.putString("contacts", toList(j.opt("contacts")).joinToString("|"))
        if (j.has("keywords")) e.putString("keywords", toList(j.opt("keywords")).joinToString("|"))
        e.apply()
    }

    @Synchronized
    fun shouldReply(sender: String, text: String, group: Boolean, study: Boolean): Boolean {
        if (!enabled()) return false
        if (text.isBlank() || text.contains("رد تلقائي") || text.contains("auto-reply", true)) return false
        if (group && !sp.getBoolean("groups", false)) return false
        if (sp.getBoolean("only_study", false) && !study) return false
        val from = sp.getInt("from", -1)
        val to = sp.getInt("to", -1)
        if (from >= 0 && to >= 0) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val inside = if (from <= to) h in from until to else (h >= from || h < to)
            if (!inside) return false
        }
        val kws = (sp.getString("keywords", "") ?: "").split("|").filter { it.isNotBlank() }
        if (kws.isNotEmpty() && kws.none { Brain.norm(text).contains(Brain.norm(it)) }) return false
        val allowed = (sp.getString("contacts", "") ?: "").split("|").map { Brain.norm(it) }.filter { it.isNotBlank() }
        val ns = Brain.norm(sender)
        val okSender = sp.getBoolean("everyone", false) || allowed.any { ns.contains(it) || (ns.length >= 3 && it.contains(ns)) }
        if (!okSender) return false
        val now = System.currentTimeMillis()
        val dk = (now / 86_400_000L).toString()
        if (dk != dayKey) { dayKey = dk; dayCount = 0 }
        if (dayCount >= 30) return false
        if (now - (last[ns] ?: 0L) < 8 * 60_000L) return false
        return true
    }

    @Synchronized
    fun noteReplied(sender: String) {
        last[Brain.norm(sender)] = System.currentTimeMillis()
        dayCount++
    }

    fun describe(): String {
        if (!enabled()) return "الرد التلقائي مقفول"
        val who = if (sp.getBoolean("everyone", false)) "أي حد" else (sp.getString("contacts", "") ?: "").replace("|", "، ").ifBlank { "محدش (مفيش أسماء)" }
        val from = sp.getInt("from", -1)
        val to = sp.getInt("to", -1)
        val time = if (from >= 0 && to >= 0) "من الساعة $from لـ $to" else "طول اليوم"
        val kw = (sp.getString("keywords", "") ?: "").replace("|", "، ")
        return "الرد التلقائي شغال على: $who. الوقت: $time." +
            (if (kw.isNotBlank()) " بس لو الرسالة فيها: $kw." else "") +
            (if (sp.getBoolean("groups", false)) " وبرد في الجروبات." else " ومبرّدش في الجروبات.") +
            (if (sp.getBoolean("only_study", false)) " بس وقت المذاكرة." else "")
    }

    /** answers through the notification's own inline-reply action (no UI automation) */
    fun sendReply(ctx: Context, action: Notification.Action, text: String): Boolean = try {
        val inputs = action.remoteInputs ?: return false
        val i = Intent()
        val b = Bundle()
        for (ri in inputs) b.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, i, b)
        action.actionIntent.send(ctx, 0, i)
        true
    } catch (_: Exception) {
        false
    }
}

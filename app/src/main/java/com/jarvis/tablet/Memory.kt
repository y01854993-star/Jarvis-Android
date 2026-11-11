package com.jarvis.tablet

import android.content.Context
import android.content.SharedPreferences

/** Small persistent list of facts Jarvis remembers about the user. */
object Memory {
    private lateinit var sp: SharedPreferences

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)
    }

    fun all(): List<String> =
        (sp.getString("facts", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    @Synchronized
    fun add(fact: String) {
        val f = fact.trim()
        if (f.isEmpty()) return
        val l = all().toMutableList()
        if (l.any { it.equals(f, true) }) return
        l.add(f)
        while (l.size > 40) l.removeAt(0)
        sp.edit().putString("facts", l.joinToString("\n")).apply()
    }
}

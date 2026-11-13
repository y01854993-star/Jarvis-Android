package com.jarvis.tablet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term, organized memory: facts (by category), taught rules, searchable notes,
 * conversation summaries, raw recent turns, and project states (for hand-offs).
 */
object Brain {
    private lateinit var file: File
    private var data = JSONObject()
    private var loaded = false

    fun norm(src: String): String {
        val sb = StringBuilder()
        for (c0 in src.lowercase()) {
            val c = when (c0) {
                '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627'
                '\u0649' -> '\u064A'
                '\u0629' -> '\u0647'
                else -> c0
            }
            if (c in '\u064B'..'\u065F' || c == '\u0640') continue
            if (c.isLetterOrDigit()) sb.append(c) else sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (s.isEmpty()) "project-" + (System.currentTimeMillis() / 1000) else s.take(40)
    }

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        file = File(ctx.filesDir, "brain.json")
        data = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        for (k in listOf("facts", "rules", "notes", "sums", "turns")) if (!data.has(k)) data.put(k, JSONArray())
        if (!data.has("projects")) data.put("projects", JSONObject())
        loaded = true
        if (!data.optBoolean("migrated")) {
            try {
                Memory.init(ctx)
                Memory.all().forEach { addFact("other", it) }
            } catch (_: Exception) {}
            data.put("migrated", true)
            save()
        }
    }

    private fun arr(k: String): JSONArray = data.getJSONArray(k)
    private fun cap(a: JSONArray, max: Int) { while (a.length() > max) a.remove(0) }
    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    @Synchronized
    private fun save() { try { file.writeText(data.toString()) } catch (_: Exception) {} }

    @Synchronized
    fun json(): String = data.toString(2)

    @Synchronized
    fun addFact(cat: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val a = arr("facts")
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("t").equals(t, true)) return
        a.put(JSONObject().put("c", cat.ifBlank { "other" }).put("t", t).put("ts", System.currentTimeMillis()))
        cap(a, 250); save()
    }

    @Synchronized
    fun addRule(trigger: String, instruction: String) {
        if (instruction.isBlank()) return
        val a = arr("rules")
        a.put(JSONObject().put("k", trigger.trim()).put("i", instruction.trim()).put("ts", System.currentTimeMillis()))
        cap(a, 60); save()
    }

    @Synchronized
    fun addNote(title: String, text: String, tags: String) {
        if (text.isBlank() && title.isBlank()) return
        val a = arr("notes")
        a.put(JSONObject().put("ti", title.trim()).put("t", text.trim()).put("tg", tags.trim()).put("ts", System.currentTimeMillis()))
        cap(a, 400); save()
    }

    @Synchronized
    fun addSummary(topic: String, text: String) {
        if (text.isBlank()) return
        val a = arr("sums")
        a.put(JSONObject().put("tp", topic.trim()).put("t", text.trim()).put("ts", System.currentTimeMillis()))
        cap(a, 150); save()
    }

    @Synchronized
    fun addTurn(u: String, r: String) {
        val a = arr("turns")
        a.put(JSONObject().put("u", u.take(600)).put("r", r.take(600)).put("ts", System.currentTimeMillis()))
        cap(a, 300); save()
    }

    @Synchronized
    fun pendingTurns(): Int = arr("turns").length()

    @Synchronized
    fun takeOldTurns(n: Int): List<Pair<String, String>> {
        val a = arr("turns")
        val out = ArrayList<Pair<String, String>>()
        val k = minOf(n, a.length())
        for (i in 0 until k) {
            val o = a.getJSONObject(0)
            out.add(o.optString("u") to o.optString("r"))
            a.remove(0)
        }
        save()
        return out
    }

    @Synchronized
    fun profile(limit: Int): List<String> {
        val a = arr("facts")
        val out = ArrayList<String>()
        var i = a.length() - 1
        while (i >= 0 && out.size < limit) {
            val o = a.getJSONObject(i)
            out.add("[" + o.optString("c") + "] " + o.optString("t"))
            i--
        }
        return out.reversed()
    }

    @Synchronized
    fun rules(limit: Int): List<String> {
        val a = arr("rules")
        val out = ArrayList<String>()
        for (i in maxOf(0, a.length() - limit) until a.length()) {
            val o = a.getJSONObject(i)
            val k = o.optString("k")
            out.add(if (k.isBlank()) o.optString("i") else "لما $k: " + o.optString("i"))
        }
        return out
    }

    @Synchronized
    fun relevant(query: String, limit: Int): List<String> {
        val toks = norm(query).split(" ").map { it.removePrefix("ال") }.filter { it.length >= 3 }.toSet()
        if (toks.isEmpty()) return emptyList()
        val cands = ArrayList<Pair<Int, String>>()
        fun consider(hay: String, shown: String) {
            val h = norm(hay)
            val s = toks.count { h.contains(it) }
            if (s > 0) cands.add(s to shown)
        }
        val notes = arr("notes")
        for (i in 0 until notes.length()) {
            val o = notes.getJSONObject(i)
            consider(o.optString("ti") + " " + o.optString("t") + " " + o.optString("tg"),
                "مذكرة «" + o.optString("ti") + "»: " + o.optString("t").take(400))
        }
        val sums = arr("sums")
        for (i in 0 until sums.length()) {
            val o = sums.getJSONObject(i)
            consider(o.optString("tp") + " " + o.optString("t"), "ملخص (" + o.optString("tp") + "): " + o.optString("t").take(400))
        }
        val facts = arr("facts")
        for (i in 0 until facts.length()) {
            val o = facts.getJSONObject(i)
            consider(o.optString("t"), "[" + o.optString("c") + "] " + o.optString("t"))
        }
        return cands.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    @Synchronized
    fun forget(query: String): Int {
        val q = norm(query)
        if (q.isEmpty()) return 0
        var n = 0
        val spec = listOf("facts" to listOf("t"), "notes" to listOf("ti", "t"), "rules" to listOf("k", "i"), "sums" to listOf("tp", "t"))
        for ((key, fields) in spec) {
            val a = arr(key)
            var i = a.length() - 1
            while (i >= 0) {
                val o = a.getJSONObject(i)
                if (norm(fields.joinToString(" ") { o.optString(it) }).contains(q)) { a.remove(i); n++ }
                i--
            }
        }
        if (n > 0) save()
        return n
    }

    // ------------------------------------------------------------ projects (for hand-off between builders)
    @Synchronized
    fun saveProject(name: String, spec: String, progress: String, builder: String) {
        val key = name.trim()
        if (key.isEmpty()) return
        val ps = data.getJSONObject("projects")
        val p = ps.optJSONObject(key) ?: JSONObject().put("spec", "").put("progress", JSONArray()).put("builder", "")
        if (spec.isNotBlank()) p.put("spec", spec)
        if (builder.isNotBlank()) p.put("builder", builder)
        if (progress.isNotBlank()) p.getJSONArray("progress").put(stamp() + " " + progress)
        p.put("ts", System.currentTimeMillis())
        ps.put(key, p)
        save()
    }

    @Synchronized
    fun appendProgress(key: String, line: String) {
        val p = data.getJSONObject("projects").optJSONObject(key) ?: return
        p.getJSONArray("progress").put(stamp() + " " + line)
        save()
    }

    @Synchronized
    fun findProject(name: String): String? {
        val ps = data.getJSONObject("projects")
        val q = norm(name)
        val keys = ps.keys().asSequence().toList()
        if (keys.isEmpty()) return null
        keys.firstOrNull { norm(it) == q }?.let { return it }
        keys.firstOrNull { q.isNotEmpty() && (norm(it).contains(q) || q.contains(norm(it))) }?.let { return it }
        return if (q.isEmpty()) keys.maxByOrNull { ps.getJSONObject(it).optLong("ts") } else null
    }

    @Synchronized
    fun projectMarkdown(key: String): String {
        val p = data.getJSONObject("projects").optJSONObject(key) ?: return ""
        val sb = StringBuilder()
        sb.append("# ").append(key).append("\n\n")
        sb.append("Builder: ").append(p.optString("builder")).append("\n")
        sb.append("Updated: ").append(stamp()).append("\n\n## Spec\n").append(p.optString("spec")).append("\n\n## Progress log\n")
        val pr = p.getJSONArray("progress")
        for (i in 0 until pr.length()) sb.append("- ").append(pr.getString(i)).append("\n")
        return sb.toString()
    }
}

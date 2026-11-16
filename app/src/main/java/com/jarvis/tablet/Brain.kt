package com.jarvis.tablet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term, organized memory: facts (by category), taught rules, searchable notes,
 * conversation summaries, recent turns, project states, learned screen-task skills,
 * and optional semantic vectors (Gemini embeddings) for meaning-based recall.
 */
object Brain {
    private lateinit var file: File
    private lateinit var embFile: File
    private lateinit var bak: File
    private var saves = 0
    private var data = JSONObject()
    private var loaded = false
    private var idc = 0L
    private val emb = HashMap<Long, FloatArray>()

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
        bak = File(ctx.filesDir, "brain.json.bak")
        embFile = File(ctx.filesDir, "brain_emb.dat")
        val loadedData = readFile(file) ?: readFile(bak)
        if (loadedData == null && file.exists()) {
            try { file.renameTo(File(ctx.filesDir, "brain.corrupt." + System.currentTimeMillis())) } catch (_: Exception) {}
        }
        data = loadedData ?: JSONObject()
        for (k in listOf("facts", "rules", "notes", "sums", "turns", "skills", "places", "macros")) if (!data.has(k)) data.put(k, JSONArray())
        if (!data.has("projects")) data.put("projects", JSONObject())
        idc = System.currentTimeMillis()
        for (k in listOf("facts", "notes", "sums")) {
            val a = arr(k)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (!o.has("id")) o.put("id", ++idc) else idc = maxOf(idc, o.optLong("id"))
            }
        }
        loadEmb()
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

    private fun newId(): Long = ++idc
    private fun arr(k: String): JSONArray = data.getJSONArray(k)
    private fun cap(a: JSONArray, max: Int) { while (a.length() > max) a.remove(0) }
    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun readFile(f: File): JSONObject? {
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            val txt = if (Vault.isEncrypted(bytes)) String(Vault.decrypt(bytes), Charsets.UTF_8) else String(bytes, Charsets.UTF_8)
            JSONObject(txt)
        } catch (_: Exception) { null }
    }

    /** encrypted at rest (Android Keystore), written atomically, with a rotating backup */
    @Synchronized
    private fun save() {
        try {
            val plain = data.toString().toByteArray(Charsets.UTF_8)
            val out = try { Vault.encrypt(plain) } catch (_: Exception) { plain }
            val tmp = File(file.parentFile, "brain.json.tmp")
            tmp.writeBytes(out)
            if (file.exists() && (++saves % 15 == 1)) { try { file.copyTo(bak, overwrite = true) } catch (_: Exception) {} }
            if (!tmp.renameTo(file)) file.writeBytes(out)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun recentTurns(n: Int): List<Pair<String, String>> {
        val a = arr("turns")
        val out = ArrayList<Pair<String, String>>()
        for (i in maxOf(0, a.length() - n) until a.length()) {
            val o = a.getJSONObject(i)
            out.add(o.optString("u") to o.optString("r"))
        }
        return out
    }

    @Synchronized
    fun json(): String = data.toString(2)

    // ------------------------------------------------------------ semantic vectors
    private fun loadEmb() {
        if (!embFile.exists()) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(embFile))).use { s ->
                while (true) {
                    val id = s.readLong()
                    val n = s.readInt()
                    if (n <= 0 || n > 4096) break
                    emb[id] = FloatArray(n) { s.readFloat() }
                }
            }
        } catch (_: EOFException) {
        } catch (_: Exception) {}
    }

    @Synchronized
    fun setEmb(id: Long, v: FloatArray) {
        emb[id] = v
        try {
            DataOutputStream(FileOutputStream(embFile, true)).use { o ->
                o.writeLong(id); o.writeInt(v.size)
                for (f in v) o.writeFloat(f)
            }
        } catch (_: Exception) {}
    }

    /** items that still have no vector: (kind, id, text) */
    @Synchronized
    fun unindexed(limit: Int): List<Triple<String, Long, String>> {
        val out = ArrayList<Triple<String, Long, String>>()
        fun scan(kind: String, textOf: (JSONObject) -> String) {
            val a = arr(kind)
            var i = a.length() - 1
            while (i >= 0 && out.size < limit) {
                val o = a.getJSONObject(i)
                val id = o.optLong("id")
                if (id != 0L && !emb.containsKey(id)) out.add(Triple(kind, id, textOf(o)))
                i--
            }
        }
        scan("notes") { it.optString("ti") + ". " + it.optString("t") }
        scan("facts") { it.optString("t") }
        scan("sums") { it.optString("tp") + ". " + it.optString("t") }
        return out.take(limit)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else d / (Math.sqrt(na) * Math.sqrt(nb))
    }

    // ------------------------------------------------------------ writes
    @Synchronized
    fun addFact(cat: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val a = arr("facts")
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("t").equals(t, true)) return
        a.put(JSONObject().put("id", newId()).put("c", cat.ifBlank { "other" }).put("t", t).put("ts", System.currentTimeMillis()))
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
        a.put(JSONObject().put("id", newId()).put("ti", title.trim()).put("t", text.trim()).put("tg", tags.trim()).put("ts", System.currentTimeMillis()))
        cap(a, 400); save()
    }

    @Synchronized
    fun addSummary(topic: String, text: String) {
        if (text.isBlank()) return
        val a = arr("sums")
        a.put(JSONObject().put("id", newId()).put("tp", topic.trim()).put("t", text.trim()).put("ts", System.currentTimeMillis()))
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

    // ------------------------------------------------------------ reads
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

    /** keyword match + (when vectors exist) meaning match */
    @Synchronized
    fun relevant(query: String, limit: Int, qEmb: FloatArray? = null): List<String> {
        val toks = norm(query).split(" ").map { it.removePrefix("ال") }.filter { it.length >= 3 }.toSet()
        if (toks.isEmpty() && qEmb == null) return emptyList()
        val cands = ArrayList<Pair<Double, String>>()
        fun consider(id: Long, hay: String, shown: String) {
            val kw = if (toks.isEmpty()) 0 else { val h = norm(hay); toks.count { h.contains(it) } }
            val v = emb[id]
            val cos = if (qEmb != null && v != null) cosine(qEmb, v) else 0.0
            val sem = if (cos > 0.55) (cos - 0.5) * 12.0 else 0.0
            val score = kw + sem
            if (score > 0) cands.add(score to shown)
        }
        val notes = arr("notes")
        for (i in 0 until notes.length()) {
            val o = notes.getJSONObject(i)
            consider(o.optLong("id"), o.optString("ti") + " " + o.optString("t") + " " + o.optString("tg"),
                "مذكرة «" + o.optString("ti") + "»: " + o.optString("t").take(400))
        }
        val sums = arr("sums")
        for (i in 0 until sums.length()) {
            val o = sums.getJSONObject(i)
            consider(o.optLong("id"), o.optString("tp") + " " + o.optString("t"), "ملخص (" + o.optString("tp") + "): " + o.optString("t").take(400))
        }
        val facts = arr("facts")
        for (i in 0 until facts.length()) {
            val o = facts.getJSONObject(i)
            consider(o.optLong("id"), o.optString("t"), "[" + o.optString("c") + "] " + o.optString("t"))
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

    // ------------------------------------------------------------ learned screen-task skills
    @Synchronized
    fun addSkill(goal: String, steps: List<String>, ok: Boolean, note: String) {
        if (steps.isEmpty()) return
        val a = arr("skills")
        a.put(JSONObject().put("g", goal.take(160)).put("st", JSONArray(steps.take(30)))
            .put("ok", ok).put("n", note.take(160)).put("ts", System.currentTimeMillis()))
        cap(a, 100); save()
    }

    @Synchronized
    fun skillsFor(goal: String, limit: Int): List<String> {
        val toks = norm(goal).split(" ").map { it.removePrefix("ال") }.filter { it.length >= 3 }.toSet()
        if (toks.isEmpty()) return emptyList()
        val a = arr("skills")
        val out = ArrayList<Pair<Int, String>>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val h = norm(o.optString("g"))
            val s = toks.count { h.contains(it) }
            if (s >= 2 || (s >= 1 && toks.size <= 2)) {
                val st = o.getJSONArray("st")
                val steps = (0 until st.length()).joinToString(" -> ") { st.getString(it) }
                val g = o.optString("g")
                out.add(s to if (o.optBoolean("ok")) "worked for goal \"$g\": $steps"
                             else "failed for goal \"$g\" (${o.optString("n")}) after: $steps - try a different path")
            }
        }
        return out.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    /** returns (and removes) the texts of notes saved with this tag */
    @Synchronized
    fun takeNotesByTag(tag: String): List<String> {
        val a = arr("notes")
        val out = ArrayList<String>()
        var i = a.length() - 1
        while (i >= 0) {
            val o = a.getJSONObject(i)
            if (o.optString("tg") == tag) { out.add(o.optString("t")); a.remove(i) }
            i--
        }
        if (out.isNotEmpty()) save()
        return out.reversed()
    }

    // ------------------------------------------------------------ places the user showed (e.g. a WhatsApp group)
    private fun aliasesOf(o: JSONObject): List<String> =
        (o.optString("alias").split("|") + o.optString("title")).map { norm(it) }.filter { it.length >= 3 }.distinct()

    @Synchronized
    fun addPlace(alias: String, pkg: String, kind: String, title: String) {
        if (alias.isBlank() || title.isBlank()) return
        val a = arr("places")
        var i = a.length() - 1
        while (i >= 0) { if (norm(a.getJSONObject(i).optString("title")) == norm(title)) a.remove(i); i-- }
        a.put(JSONObject().put("alias", alias.trim()).put("pkg", pkg).put("kind", kind).put("title", title.trim())
            .put("ts", System.currentTimeMillis()))
        cap(a, 120); save()
    }

    @Synchronized
    fun findPlace(spoken: String): JSONObject? {
        val q = norm(spoken)
        if (q.length < 3) return null
        val a = arr("places")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (aliasesOf(o).any { it == q || q.contains(it) || it.contains(q) }) return o
        }
        return null
    }

    @Synchronized
    fun findPlaceByText(text: String): JSONObject? {
        val q = norm(text)
        val a = arr("places")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (aliasesOf(o).any { q.contains(it) }) return o
        }
        return null
    }

    @Synchronized
    fun placeNames(): List<String> {
        val a = arr("places")
        return (0 until a.length()).map { a.getJSONObject(it).optString("alias").ifBlank { a.getJSONObject(it).optString("title") } }
    }

    // ------------------------------------------------------------ routines recorded from a demonstration
    @Synchronized
    fun addMacro(name: String, trigger: String, steps: JSONArray) {
        if (name.isBlank() || steps.length() == 0) return
        val a = arr("macros")
        var i = a.length() - 1
        while (i >= 0) { if (norm(a.getJSONObject(i).optString("name")) == norm(name)) a.remove(i); i-- }
        a.put(JSONObject().put("name", name.trim()).put("trigger", trigger.trim()).put("steps", steps).put("ts", System.currentTimeMillis()))
        cap(a, 60); save()
    }

    @Synchronized
    fun findMacro(name: String): JSONObject? {
        val q = norm(name)
        if (q.length < 3) return null
        val a = arr("macros")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val n = norm(o.optString("name"))
            if (n == q || n.contains(q) || q.contains(n)) return o
        }
        return null
    }

    @Synchronized
    fun findMacroByText(text: String): JSONObject? {
        val q = norm(text)
        val a = arr("macros")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val triggers = (o.optString("trigger").split("|") + o.optString("name")).map { norm(it) }.filter { it.length >= 4 }
            if (triggers.any { q.contains(it) }) return o
        }
        return null
    }

    @Synchronized
    fun macroNames(): List<String> {
        val a = arr("macros")
        return (0 until a.length()).map { a.getJSONObject(it).optString("name") }
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

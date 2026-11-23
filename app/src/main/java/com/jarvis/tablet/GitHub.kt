package com.jarvis.tablet

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal GitHub contents API client (saves memory, projects and hand-off briefs to the user's repo). */
object GitHub {
    fun configured(): Boolean = Keys.ghToken.isNotBlank() && Keys.ghRepo.contains("/")

    private fun enc(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun req(method: String, path: String, body: JSONObject? = null, repo: String = Keys.ghRepo): Pair<Int, String> {
        val conn = URL("https://api.github.com/repos/$repo/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer ${Keys.ghToken}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    fun read(path: String): String? {
        val (c, t) = req("GET", "contents/" + enc(path))
        if (c != 200) return null
        return try {
            val j = JSONObject(t)
            String(Base64.decode(j.getString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun write(path: String, content: String, message: String): Boolean {
        val p = enc(path)
        var sha: String? = null
        val (c, t) = req("GET", "contents/$p")
        if (c == 200) sha = try { JSONObject(t).optString("sha") } catch (_: Exception) { null }
        val body = JSONObject().put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!sha.isNullOrEmpty()) body.put("sha", sha)
        val (c2, _) = req("PUT", "contents/$p", body)
        return c2 == 200 || c2 == 201
    }

    /** open pull requests opened by the autofix bot: (number, title, url) */
    fun openFixPrs(): List<Triple<Int, String, String>> {
        val (c, t) = req("GET", "pulls?state=open&per_page=30", null, Keys.fixRepo)
        if (c != 200) return emptyList()
        val arr = JSONArray(t)
        val out = ArrayList<Triple<Int, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ref = o.optJSONObject("head")?.optString("ref") ?: ""
            if (ref.startsWith("autofix/")) out.add(Triple(o.optInt("number"), o.optString("title"), o.optString("html_url")))
        }
        return out
    }

    /** merges ONLY a pull request that comes from an autofix/ branch */
    fun mergeFixPr(number: Int): Pair<Boolean, String> {
        val (c0, t0) = req("GET", "pulls/$number", null, Keys.fixRepo)
        if (c0 != 200) return false to "مش لاقي الإصلاح رقم $number"
        val ref = JSONObject(t0).optJSONObject("head")?.optString("ref") ?: ""
        if (!ref.startsWith("autofix/")) return false to "ده مش إصلاح من بوت المشاكل"
        val (c, _) = req("PUT", "pulls/$number/merge", JSONObject().put("merge_method", "squash"), Keys.fixRepo)
        return if (c == 200) true to "اتدمج" else false to "الدمج فشل ($c)"
    }

    /** numbers for the ENGINEER dashboard, read from the fix repo (issues labelled jarvis-problem, autofix pull requests, last build) */
    fun fixStats(): JSONObject {
        val repo = Keys.fixRepo
        val out = JSONObject()
        val (ci, ti) = req("GET", "issues?labels=jarvis-problem&state=all&per_page=100", null, repo)
        if (ci != 200) return out
        var total = 0; var fresh = 0; var investigating = 0; var awaiting = 0
        var rejected = 0; var resolved = 0; var failed = 0; var noFix = 0
        val issues = JSONArray(ti)
        for (i in 0 until issues.length()) {
            val o = issues.getJSONObject(i)
            if (o.has("pull_request")) continue
            total++
            val names = HashSet<String>()
            val ls = o.optJSONArray("labels")
            if (ls != null) for (j in 0 until ls.length()) names.add(ls.getJSONObject(j).optString("name"))
            when {
                "status:resolved" in names -> resolved++
                "status:rejected" in names -> rejected++
                "status:awaiting-approval" in names -> awaiting++
                "status:investigating" in names -> investigating++
                "status:failed" in names -> failed++
                "status:no-code-fix" in names -> noFix++
                o.optString("state") == "open" -> fresh++
            }
        }
        var approved = 0
        val prs = JSONArray()
        val (cp, tp) = req("GET", "pulls?state=all&per_page=30", null, repo)
        if (cp == 200) {
            val arr = JSONArray(tp)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ref = o.optJSONObject("head")?.optString("ref") ?: ""
                if (!ref.startsWith("autofix/")) continue
                if (o.optString("state") == "open") prs.put(JSONObject().put("n", o.optInt("number")).put("title", o.optString("title")).put("url", o.optString("html_url")))
                else if (!o.isNull("merged_at")) approved++
            }
        }
        var build = "--"
        val (cr, tr) = req("GET", "actions/workflows/build.yml/runs?per_page=1", null, repo)
        if (cr == 200) {
            val runs = JSONObject(tr).optJSONArray("workflow_runs")
            if (runs != null && runs.length() > 0) {
                val r = runs.getJSONObject(0)
                build = (if (r.isNull("conclusion")) r.optString("status") else r.optString("conclusion")) + " (" + r.optString("head_branch") + ")"
            }
        }
        return out.put("total", total).put("fresh", fresh).put("investigating", investigating).put("awaiting", awaiting)
            .put("approved", approved).put("rejected", rejected).put("resolved", resolved).put("failed", failed)
            .put("noFix", noFix).put("prs", prs).put("build", build)
            .put("releases", "https://github.com/$repo/releases").put("actions", "https://github.com/$repo/actions")
    }

    fun createIssue(title: String, body: String, labels: List<String>): Boolean {
        val j = JSONObject().put("title", title.take(200)).put("body", body.take(60000)).put("labels", JSONArray(labels))
        val (c, _) = req("POST", "issues", j, Keys.fixRepo)
        return c == 201
    }

    fun tree(): List<String> {
        val (c, t) = req("GET", "git/trees/HEAD?recursive=1")
        if (c != 200) return emptyList()
        val out = ArrayList<String>()
        return try {
            val a: JSONArray = JSONObject(t).getJSONArray("tree")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("type") == "blob") out.add(o.optString("path"))
            }
            out
        } catch (_: Exception) { out }
    }
}

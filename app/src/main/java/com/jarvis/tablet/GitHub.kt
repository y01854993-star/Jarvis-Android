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

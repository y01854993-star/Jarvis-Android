package com.jarvis.tablet

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Problems log: kept inside the app, and exported as a real file Documents/JARVIS/problems.md (no storage permission needed). */
object Problems {
    private lateinit var file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private var lastText = ""
    private var lastAt = 0L

    @Synchronized
    fun init(ctx: Context) {
        if (::file.isInitialized) return
        val d = File(ctx.filesDir, "JARVIS")
        d.mkdirs()
        file = File(d, "problems.md")
        if (!file.exists()) file.writeText("# JARVIS problems log\n\n")
    }

    @Synchronized
    fun add(text: String) {
        if (!::file.isInitialized) return
        val t = text.replace('\n', ' ').take(300)
        val now = System.currentTimeMillis()
        if (t == lastText && now - lastAt < 60_000) return
        lastText = t; lastAt = now
        try {
            if (file.length() > 200_000) {
                val keep = file.readLines().takeLast(400)
                file.writeText("# JARVIS problems log\n\n" + keep.joinToString("\n") + "\n")
            }
            file.appendText("- ${fmt.format(Date())} - $t\n")
        } catch (_: Exception) {}
    }

    @Synchronized
    fun lines(): List<String> =
        if (!::file.isInitialized) emptyList()
        else try { file.readLines().filter { it.startsWith("- ") } } catch (_: Exception) { emptyList() }

    fun summary(): String {
        val l = lines()
        if (l.isEmpty()) return "مفيش مشاكل متسجلة، كله تمام"
        val last = l.takeLast(3).joinToString("، ") { it.substringAfter(" - ").substringAfter(" - ").take(80) }
        return "عندي ${l.size} مشكلة متسجلة. آخر حاجات: $last"
    }

    private fun findUri(ctx: Context): Uri? {
        val coll = MediaStore.Files.getContentUri("external")
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        return ctx.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID), sel, arrayOf("problems.md", "Documents/JARVIS/"), null)
            ?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(coll, c.getLong(0)) else null }
    }

    /** writes/updates Documents/JARVIS/problems.md */
    fun export(ctx: Context): Uri? {
        if (Build.VERSION.SDK_INT < 29 || !::file.isInitialized) return null
        return try {
            val text = file.readText()
            val coll = MediaStore.Files.getContentUri("external")
            var uri = findUri(ctx)
            if (uri == null) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "problems.md")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/JARVIS/")
                }
                uri = ctx.contentResolver.insert(coll, v)
            }
            uri?.also { u -> ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(text.toByteArray()) } }
        } catch (_: Exception) { null }
    }
}

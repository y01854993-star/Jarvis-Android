package com.jarvis.tablet

/** Masks keys, e-mails and long numbers before any text leaves the phone (reports, AI diagnosis). Pure logic, unit tested. */
object Redact {
    private val keyPatterns = listOf(
        Regex("ghp_[A-Za-z0-9]{20,}"), Regex("github_pat_[A-Za-z0-9_]{20,}"), Regex("AIza[0-9A-Za-z_\\-]{30,}"),
        Regex("sk-[A-Za-z0-9_\\-]{20,}"), Regex("gsk_[A-Za-z0-9]{20,}"), Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{20,}")
    )
    private val email = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val number = Regex("\\+?\\d[\\d\\s-]{6,}\\d")

    fun mask(s: String): String {
        var r = s
        for (p in keyPatterns) r = p.replace(r, "<key>")
        r = email.replace(r, "<email>")
        return number.replace(r, "<number>")
    }
}

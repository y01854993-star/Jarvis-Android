package com.jarvis.tablet.domain.vision

/**
 * Screens are private. A vision model may only be reached over HTTPS, or over plain HTTP when the server is on this phone
 * or on a private network (home server, Tailscale), never a public host in clear text.
 */
object VlmEndpointPolicy {
    fun isAllowed(url: String): Boolean {
        val u = url.trim()
        if (u.startsWith("https://")) return u.length > 8
        if (!u.startsWith("http://")) return false
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".ts.net")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 127 || p[0] == 10 || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)
    }
}

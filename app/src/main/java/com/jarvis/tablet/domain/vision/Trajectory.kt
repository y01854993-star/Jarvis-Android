package com.jarvis.tablet.domain.vision

/** One step of an agent episode: what was on screen (as a digest), what the agent did, and whether it worked. Training data for sequence models. */
class TrajectoryStep(
    val episode: String,
    val index: Int,
    val goal: String,
    val action: String,
    val target: String,
    val success: Boolean,
    val screenDigest: Long,
    val frameFile: String?,
    val timestampMs: Long
)

/** JSON Lines encoding written by hand so the domain stays free of Android and org.json. */
object TrajectoryJsonl {
    fun encode(s: TrajectoryStep): String = buildString {
        append('{')
        append("\"episode\":").append(quote(s.episode)).append(',')
        append("\"index\":").append(s.index).append(',')
        append("\"goal\":").append(quote(s.goal)).append(',')
        append("\"action\":").append(quote(s.action)).append(',')
        append("\"target\":").append(quote(s.target)).append(',')
        append("\"success\":").append(s.success).append(',')
        append("\"screen_digest\":").append(quote(java.lang.Long.toHexString(s.screenDigest))).append(',')
        append("\"frame\":").append(s.frameFile?.let { quote(it) } ?: "null").append(',')
        append("\"ts\":").append(s.timestampMs)
        append('}')
    }

    fun quote(v: String): String = buildString {
        append('"')
        for (c in v) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(String.format("%04x", c.code))
            else -> append(c)
        }
        append('"')
    }
}

package com.jarvis.tablet.domain.vision

/**
 * Rolling visual memory: the last few observations are kept verbatim, older ones are compressed into a short summary.
 * Gives a follow-up question ("and then?") the context of what was already seen, within a fixed size budget.
 */
class SequentialContext(private val windowSize: Int = 4, private val summaryChars: Int = 400) {
    class Entry(val timestampMs: Long, val source: FrameSource, val text: String)

    private val recent = ArrayDeque<Entry>()
    private var older = ""

    val size: Int get() = recent.size

    fun add(entry: Entry) {
        recent.addLast(entry)
        while (recent.size > windowSize) {
            val old = recent.removeFirst()
            older = (older + " " + old.text.trim().take(120)).trim().takeLast(summaryChars)
        }
    }

    fun clear() {
        recent.clear()
        older = ""
    }

    /** text for the model prompt, never longer than [maxChars] (the oldest part is cut first) */
    fun asPrompt(nowMs: Long, maxChars: Int = 1200): String {
        if (recent.isEmpty() && older.isEmpty()) return ""
        val sb = StringBuilder()
        if (older.isNotEmpty()) sb.append("Earlier (summary): ").append(older).append('\n')
        for (e in recent) {
            val ago = ((nowMs - e.timestampMs) / 1000).coerceAtLeast(0)
            sb.append("- ").append(ago).append("s ago [").append(e.source.name.lowercase()).append("]: ").append(e.text.trim().take(300)).append('\n')
        }
        val s = sb.toString().trimEnd()
        return if (s.length <= maxChars) s else s.takeLast(maxChars)
    }
}

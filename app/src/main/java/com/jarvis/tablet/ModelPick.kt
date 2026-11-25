package com.jarvis.tablet

/** Picks working model ids from what the provider really offers, so a retired model name cannot cause endless 404s. Pure logic, unit tested. */
object ModelPick {
    private val excluded = Regex("whisper|guard|tts|embed|moderation|orpheus|prompt-guard|safeguard", RegexOption.IGNORE_CASE)

    /**
     * @param available ids returned by the provider's /models endpoint
     * @param configured model names from the app config (tried first when they really exist)
     * @param preferred ordered fallbacks
     * @param pattern last-resort match
     * @return usable ids in order, or an empty list when nothing can be decided (the caller keeps the configured ones)
     */
    fun choose(available: List<String>, configured: List<String>, preferred: List<String>, pattern: Regex?): List<String> {
        val keep = configured.filter { it in available }
        if (keep.isNotEmpty()) return keep
        val pref = preferred.filter { it in available }
        if (pref.isNotEmpty()) return pref
        val usable = available.filter { !excluded.containsMatchIn(it) }
        return (pattern?.let { p -> usable.filter { p.containsMatchIn(it) } } ?: emptyList()).take(2)
    }
}

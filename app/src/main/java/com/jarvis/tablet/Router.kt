package com.jarvis.tablet

/**
 * Smart model router (pure logic: no network, no Android classes, unit tested).
 * 1. classify the request, 2. build an ordered shortlist for that task, 3. the caller tries ONE provider and moves on only on failure.
 * The shortlist is capped (default 3 attempts) so a bad moment never burns every provider's quota.
 */
object Router {
    enum class Task { CHAT, REASONING, CODING, TRANSLATION, SUMMARIZATION, PLANNING, VISION, ANDROID_CONTROL }

    val PROVIDERS = listOf("gemini", "groq", "deepseek", "openai", "kimi")

    private val coding = Regex("(code|python|kotlin|\\bjava\\b|javascript|typescript|bug|debug|stack ?trace|exception|compile|\\bsql\\b|regex|function|\\bapi\\b|\\bgit\\b|برمج|كود|سكريبت|دالة)", RegexOption.IGNORE_CASE)
    private val translation = Regex("(translate|translation|ترجم|ترجمة|بالانجليزي|بالإنجليزي)", RegexOption.IGNORE_CASE)
    private val summary = Regex("(summari[sz]e|summary|tl;?dr|لخص|ملخص|اختصر)", RegexOption.IGNORE_CASE)
    private val planning = Regex("(\\bplan\\b|steps to|roadmap|schedule|organi[sz]e|خطة|جدول|رتب|نظم|مراحل)", RegexOption.IGNORE_CASE)
    private val reasoning = Regex("(analy[sz]e|compare|explain why|strategy|proof|equation|calculate|حلل|قارن|اشرح بالتفصيل|ليه|ازاي|استراتيج|اثبت|خوارزم)", RegexOption.IGNORE_CASE)

    fun classify(text: String): Task = when {
        coding.containsMatchIn(text) -> Task.CODING
        translation.containsMatchIn(text) -> Task.TRANSLATION
        summary.containsMatchIn(text) -> Task.SUMMARIZATION
        planning.containsMatchIn(text) -> Task.PLANNING
        text.length > 220 || reasoning.containsMatchIn(text) -> Task.REASONING
        else -> Task.CHAT
    }

    /** sensible defaults per task; the user can override any of them (voice: "route_set") */
    fun defaultOrder(t: Task): List<String> = when (t) {
        Task.CHAT -> listOf("gemini", "groq", "deepseek", "openai", "kimi")
        Task.CODING -> listOf("deepseek", "gemini", "openai", "kimi", "groq")
        Task.REASONING -> listOf("deepseek", "gemini", "openai", "kimi", "groq")
        Task.PLANNING -> listOf("gemini", "deepseek", "openai", "kimi", "groq")
        Task.TRANSLATION, Task.SUMMARIZATION -> listOf("gemini", "kimi", "groq", "deepseek", "openai")
        Task.VISION, Task.ANDROID_CONTROL -> listOf("gemini", "openai", "groq")
    }

    /**
     * @param taskPref provider the user chose for this task type (or null)
     * @param manual   the global manual choice ("auto" or a provider)
     * @param available providers that have a key
     * @param disabled  providers in cooldown
     */
    fun order(task: Task, taskPref: String?, manual: String?, available: Set<String>, disabled: Set<String>, maxAttempts: Int = 3): List<String> {
        val base = defaultOrder(task)
        val first = listOfNotNull(taskPref?.takeIf { it in PROVIDERS }, manual?.takeIf { it in PROVIDERS })
        val wanted = (first + base).distinct().filter { it in available }
        val usable = wanted.filter { it !in disabled }.ifEmpty { wanted }
        return usable.take(maxAttempts.coerceAtLeast(1))
    }
}

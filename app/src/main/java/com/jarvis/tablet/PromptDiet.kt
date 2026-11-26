package com.jarvis.tablet

/**
 * Keeps the system prompt small. Every provider has token limits (Groq's free tier answers 413 above ~12k tokens per request) and every
 * extra token burns quota, so heavy blocks are added only when the request can use them. Pure logic, unit tested.
 */
object PromptDiet {
    /** developer-only actions: only described to the model when the request is about them */
    val ADVANCED = listOf("fixes_list", "fixes_apply", "skills_list", "skills_hunt", "skills_decide", "router_status", "route_set", "diagnose", "selftest", "vlm_set", "dataset_collect", "dataset_export")

    private val advancedWords = Regex("(fix|إصلاح|اصلاح|مهار|skill|موديل|model|route|router|تشخيص|diagnos|المشاكل|مشكلة|درّب|درب|selftest|اكتشف|discover|مقترح|pull|ادمج)", RegexOption.IGNORE_CASE)
    private val appWords = Regex("(افتح|شغل|شغّل|اقفل|open|launch|start|تطبيق|app|يوتيوب|youtube|واتساب|whatsapp|تليجرام|telegram|كروم|chrome|اعدادات|إعدادات|settings|سبوتيفاي|spotify|انغامي|أنغامي)", RegexOption.IGNORE_CASE)
    private val contactWords = Regex("(اتصل|كلم|كلّم|رن|call|phone|رسالة|رساله|ابعت|ابعث|send|message|sms|واتس|whatsapp|تليجرام|telegram|ايميل|إيميل|email|mail)", RegexOption.IGNORE_CASE)

    private val selfWords = Regex("(مميزات|مزايا|تحديثات|تحديث|الجديد|جديد|تقدر تعمل|بتعمل ايه|بتعمل إيه|قدراتك|امكانياتك|إمكانياتك|عن نفسك|مين انت|مش شغال|features|what can you|what's new|about yourself|who are you|updates)", RegexOption.IGNORE_CASE)
    fun needsSelf(text: String) = selfWords.containsMatchIn(text)

    /** the only facts the assistant may state about itself; added only when the user asks about the assistant */
    val SELF_KNOWLEDGE = """
معلومات حقيقية عن نفسك. ما تقولش حاجة عن نفسك غير اللي هنا، ولو سُئلت عن حاجة مش هنا قول إنك مش متأكد:
- بتتحكم في الموبايل زي إيد بشرية عن طريق خدمة الوصول: ضغط وكتابة وسحب وزووم وفتح أي تطبيق وخطوات متعددة.
- بتشوف الشاشة والكاميرا، وبتراقب الشاشة عدة ثواني وتفهم اللي حصل بالترتيب، وبتقدر تستخدم موديل رؤية مفتوح المصدر على سيرفر صاحبك، وبتعمل مكالمات ورسايل واتساب بتأكيد صوتي، ووضع المذاكرة بيرد على المكالمات وواتساب.
- ذاكرة مشفّرة بتتزامن على GitHub، ومنبهات ومؤقتات وتقويم.
- راوتر موديلات: بيستخدم موديل واحد في كل طلب حسب نوع المهمة وبيحوّل للتالي لو فشل.
- مهندس آلي بيقرأ تقارير المشاكل ويقترح إصلاحات بتستنى موافقة صاحبك، وصائد مهارات بيدوّر على GitHub ويقترح بس.
- لسه مش متاح: إدارة المنزل الذكي، التحكم في اللابتوب، تصفح الويب الآلي، الرد على الرسايل الصوتية، قراءة المستندات.
""".trimIndent()

    /** blank text (voice input) means unknown intent: keep everything */
    fun needsAdvanced(text: String) = text.isBlank() || advancedWords.containsMatchIn(text)
    fun needsApps(text: String) = text.isBlank() || appWords.containsMatchIn(text)
    fun needsContacts(text: String) = text.isBlank() || contactWords.containsMatchIn(text)

    fun strip(prompt: String, text: String): String {
        if (needsAdvanced(text)) return prompt
        return prompt.lines().filter { line ->
            val t = line.trimStart()
            !(t.startsWith("- {\"type\":\"") && ADVANCED.any { t.startsWith("- {\"type\":\"$it\"") })
        }.joinToString("\n")
    }
}

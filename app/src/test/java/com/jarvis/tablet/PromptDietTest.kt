package com.jarvis.tablet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDietTest {
    private val prompt = listOf(
        "intro line",
        "- {\"type\":\"open_app\"}: opens an app",
        "- {\"type\":\"diagnose\"}: checks things",
        "- {\"type\":\"fixes_apply\",\"number\":N}: merges",
        "- {\"type\":\"selftest\"}: trains",
        "closing line"
    ).joinToString("\n")

    @Test fun smallTalkDropsDeveloperActions() {
        val out = PromptDiet.strip(prompt, "صباح الخير")
        assertFalse(out.contains("diagnose"))
        assertFalse(out.contains("fixes_apply"))
        assertFalse(out.contains("selftest"))
        assertTrue(out.contains("open_app"))
        assertTrue(out.contains("intro line") && out.contains("closing line"))
    }

    @Test fun developerRequestsKeepEverything() {
        assertTrue(PromptDiet.strip(prompt, "شوف المشاكل").contains("diagnose"))
        assertTrue(PromptDiet.strip(prompt, "ادمج الإصلاح رقم 3").contains("fixes_apply"))
        assertTrue(PromptDiet.strip(prompt, "درب نفسك").contains("selftest"))
    }

    @Test fun blankTextMeansVoiceSoKeepEverything() {
        assertTrue(PromptDiet.strip(prompt, "").contains("diagnose"))
        assertTrue(PromptDiet.needsApps("") && PromptDiet.needsContacts(""))
    }

    @Test fun appsAndContactsOnlyWhenRelevant() {
        assertFalse(PromptDiet.needsApps("ازيك عامل ايه"))
        assertTrue(PromptDiet.needsApps("افتح يوتيوب"))
        assertFalse(PromptDiet.needsContacts("احكيلي نكتة"))
        assertTrue(PromptDiet.needsContacts("كلم ماما"))
        assertTrue(PromptDiet.needsContacts("ابعت رسالة لأحمد"))
    }

    @Test fun selfKnowledgeOnlyWhenAskedAboutTheAssistant() {
        assertTrue(PromptDiet.needsSelf("ايه المميزات الجديدة عندك"))
        assertTrue(PromptDiet.needsSelf("what can you do"))
        assertFalse(PromptDiet.needsSelf("افتح يوتيوب"))
        assertFalse(PromptDiet.needsSelf(""))
        assertTrue(PromptDiet.SELF_KNOWLEDGE.contains("لسه مش متاح"))
    }
}

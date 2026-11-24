package com.jarvis.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    private val all = Router.PROVIDERS.toSet()

    @Test fun classifiesCoding() {
        assertEquals(Router.Task.CODING, Router.classify("اكتب كود python يقرأ ملف"))
        assertEquals(Router.Task.CODING, Router.classify("fix this kotlin bug"))
    }

    @Test fun classifiesOtherTasks() {
        assertEquals(Router.Task.TRANSLATION, Router.classify("ترجم الجملة دي للانجليزي"))
        assertEquals(Router.Task.SUMMARIZATION, Router.classify("لخص لي الموضوع ده"))
        assertEquals(Router.Task.PLANNING, Router.classify("اعمل خطة مذاكرة"))
        assertEquals(Router.Task.REASONING, Router.classify("حلل الفرق بين الخيارين"))
        assertEquals(Router.Task.CHAT, Router.classify("صباح الخير"))
        assertEquals(Router.Task.REASONING, Router.classify("a".repeat(300)))
    }

    @Test fun usesOneModelFirstAndCapsAttempts() {
        val o = Router.order(Router.Task.CHAT, null, "auto", all, emptySet())
        assertEquals(3, o.size)
        assertEquals("gemini", o.first())
    }

    @Test fun neverReturnsProvidersWithoutKeys() {
        val o = Router.order(Router.Task.CODING, null, "auto", setOf("groq"), emptySet())
        assertEquals(listOf("groq"), o)
        assertTrue(Router.order(Router.Task.CHAT, null, "auto", emptySet(), emptySet()).isEmpty())
    }

    @Test fun skipsProvidersInCooldown() {
        val o = Router.order(Router.Task.CHAT, null, "auto", all, setOf("gemini"))
        assertTrue("gemini" !in o)
        assertEquals("groq", o.first())
    }

    @Test fun fallsBackToCooldownProvidersWhenNothingElseExists() {
        val o = Router.order(Router.Task.CHAT, null, "auto", setOf("gemini"), setOf("gemini"))
        assertEquals(listOf("gemini"), o)
    }

    @Test fun userPreferenceWinsThenGlobalChoice() {
        assertEquals("kimi", Router.order(Router.Task.CHAT, "kimi", "auto", all, emptySet()).first())
        assertEquals("openai", Router.order(Router.Task.CHAT, null, "openai", all, emptySet()).first())
        assertEquals("kimi", Router.order(Router.Task.CHAT, "kimi", "openai", all, emptySet()).first())
    }

    @Test fun unknownPreferenceIsIgnored() {
        assertEquals("gemini", Router.order(Router.Task.CHAT, "nonsense", "auto", all, emptySet()).first())
    }

    @Test fun agentTasksOnlyUseVisionCapableProviders() {
        val o = Router.order(Router.Task.ANDROID_CONTROL, null, "auto", all, emptySet())
        assertTrue(o.all { it in setOf("gemini", "openai", "groq") })
    }

    @Test fun maxAttemptsIsAtLeastOne() {
        assertEquals(1, Router.order(Router.Task.CHAT, null, "auto", all, emptySet(), 0).size)
    }

    @Test fun oversizedPromptsSkipGroq() {
        val big = Router.order(Router.Task.CHAT, null, "auto", all, emptySet(), 3, 30000)
        assertTrue("groq" !in big)
        val small = Router.order(Router.Task.CHAT, null, "auto", all, emptySet(), 5, 5000)
        assertTrue("groq" in small)
    }

    @Test fun oversizedPromptStillWorksWhenGroqIsTheOnlyKey() {
        assertEquals(listOf("groq"), Router.order(Router.Task.CHAT, null, "auto", setOf("groq"), emptySet(), 3, 90000))
    }
}

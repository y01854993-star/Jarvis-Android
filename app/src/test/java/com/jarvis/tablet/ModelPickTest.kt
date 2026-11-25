package com.jarvis.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickTest {
    private val groq = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "whisper-large-v3", "meta-llama/llama-guard-4-12b", "meta-llama/llama-4-scout-17b-16e-instruct")

    @Test fun keepsConfiguredModelsThatExist() {
        assertEquals(listOf("llama-3.3-70b-versatile"), ModelPick.choose(groq, listOf("llama-3.3-70b-versatile", "retired-model"), emptyList(), null))
    }

    @Test fun retiredModelFallsBackToPreferred() {
        val r = ModelPick.choose(groq, listOf("llama-3.1-70b-versatile"), listOf("llama-3.3-70b-versatile"), null)
        assertEquals(listOf("llama-3.3-70b-versatile"), r)
    }

    @Test fun patternNeverPicksWhisperOrGuardModels() {
        val r = ModelPick.choose(groq, listOf("gone"), listOf("also-gone"), Regex("llama|whisper|guard", RegexOption.IGNORE_CASE))
        assertTrue(r.none { it.contains("whisper") || it.contains("guard") })
        assertTrue(r.isNotEmpty())
    }

    @Test fun unknownEverythingReturnsEmptySoCallerKeepsConfig() {
        assertTrue(ModelPick.choose(listOf("x"), listOf("a"), listOf("b"), null).isEmpty())
    }
}

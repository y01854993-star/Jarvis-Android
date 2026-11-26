package com.jarvis.tablet.domain.vision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun gridOf(seed: Int): IntArray = IntArray(72) { (it * seed * 37 + seed * 11) % 256 }
private fun frame(t: Long, seed: Int, src: FrameSource = FrameSource.SCREEN) = Frame(t, src, 100, 200, byteArrayOf(1, 2, 3), gridOf(seed))

class PerceptualHashTest {
    @Test fun identicalGridsHaveZeroDistance() {
        assertEquals(0, PerceptualHash.distance(PerceptualHash.dHash(gridOf(5)), PerceptualHash.dHash(gridOf(5))))
    }

    @Test fun veryDifferentGridsAreFarApart() {
        val rising = IntArray(72) { (it % 9) * 20 }
        val falling = IntArray(72) { 255 - (it % 9) * 20 }
        assertTrue(PerceptualHash.distance(PerceptualHash.dHash(rising), PerceptualHash.dHash(falling)) > 40)
    }

    @Test fun smallBrightnessShiftBarelyChangesTheHash() {
        val a = gridOf(9)
        val b = IntArray(72) { a[it] + 3 }
        assertTrue(PerceptualHash.distance(PerceptualHash.dHash(a), PerceptualHash.dHash(b)) <= 2)
    }

    @Test(expected = IllegalArgumentException::class) fun wrongSizeIsRejected() { PerceptualHash.dHash(IntArray(10)) }
}

class KeyframeSelectorTest {
    @Test fun identicalFramesCollapseToFirstAndLast() {
        val s = KeyframeSelector(minDistance = 6, maxGapMs = 10_000, maxFrames = 4)
        for (i in 0 until 6) s.offer(frame(i * 1000L, 3))
        val r = s.result()
        assertEquals(2, r.size)
        assertEquals(0L, r.first().timestampMs)
        assertEquals(5000L, r.last().timestampMs)
    }

    @Test fun changedFramesAreKeptUpToTheBudget() {
        val s = KeyframeSelector(minDistance = 6, maxGapMs = 10_000, maxFrames = 3)
        for (i in 1..8) s.offer(frame(i * 1000L, i * 7))
        val r = s.result()
        assertTrue(r.size <= 3)
        assertEquals(8000L, r.last().timestampMs)
        assertEquals(1000L, r.first().timestampMs)
    }

    @Test fun staleFrameIsKeptAfterTheMaxGap() {
        val s = KeyframeSelector(minDistance = 64, maxGapMs = 2000, maxFrames = 4)
        assertTrue(s.offer(frame(0, 3)))
        assertFalse(s.offer(frame(1000, 3)))
        assertTrue(s.offer(frame(2500, 3)))
    }

    @Test fun emptyWhenNothingOffered() { assertTrue(KeyframeSelector().result().isEmpty()) }
}

class SequentialContextTest {
    @Test fun emptyContextGivesEmptyPrompt() { assertEquals("", SequentialContext().asPrompt(0)) }

    @Test fun recentEntriesAreKeptAndOldOnesSummarised() {
        val c = SequentialContext(windowSize = 2)
        for (i in 1..4) c.add(SequentialContext.Entry(i * 1000L, FrameSource.SCREEN, "event $i"))
        val p = c.asPrompt(5000)
        assertEquals(2, c.size)
        assertTrue(p.contains("event 3") && p.contains("event 4"))
        assertTrue(p.contains("Earlier") && p.contains("event 1"))
    }

    @Test fun promptNeverExceedsTheBudget() {
        val c = SequentialContext(windowSize = 4)
        for (i in 1..4) c.add(SequentialContext.Entry(i * 1000L, FrameSource.CAMERA, "x".repeat(500)))
        assertTrue(c.asPrompt(9000, 300).length <= 300)
    }

    @Test fun clearForgetsEverything() {
        val c = SequentialContext()
        c.add(SequentialContext.Entry(0, FrameSource.SCREEN, "a"))
        c.clear()
        assertEquals("", c.asPrompt(1))
    }
}

class VlmPromptTest {
    @Test fun multiFramePromptExplainsTheTimeline() {
        val p = VlmPrompt.build(VlmRequest(listOf(frame(0, 1), frame(2000, 2)), "what happened?", ""))
        assertTrue(p.contains("2 images") && p.contains("+2.0s") && p.contains("what happened?"))
    }

    @Test fun contextIsIncludedWhenPresent() {
        val p = VlmPrompt.build(VlmRequest(listOf(frame(0, 1)), "and now?", "- 5s ago [screen]: a login page"))
        assertTrue(p.contains("a login page"))
    }
}

class WatchSequenceTest {
    private class FakeSampler(val frames: List<Frame?>) : FrameSampler {
        var i = 0
        override suspend fun capture(): Frame? = frames.getOrNull(i++)
    }

    private class FakeVlm : VisionLanguageModel {
        var last: VlmRequest? = null
        override suspend fun describe(request: VlmRequest): VlmResult { last = request; return VlmResult("a video started", "fake") }
    }

    @Test fun watchesFiltersAndAsksOnce() = runBlocking {
        var clock = 0L
        val sampler = FakeSampler((0 until 10).map { frame(it * 1000L, if (it < 5) 3 else 8) })
        val vlm = FakeVlm()
        val ctx = SequentialContext()
        val uc = WatchSequence(sampler, vlm, ctx, { clock }) { clock += it }
        val r = uc(seconds = 5, intervalMs = 1000, question = "what happened?")
        assertNotNull(r)
        assertEquals("a video started", r!!.text)
        val sent = vlm.last!!.frames
        assertTrue(sent.size in 2..4)
        assertEquals(1, ctx.size)
    }

    @Test fun returnsNullWhenNothingCouldBeCaptured() = runBlocking {
        var clock = 0L
        val uc = WatchSequence(FakeSampler(listOf(null, null, null)), FakeVlm(), SequentialContext(), { clock }) { clock += it }
        assertNull(uc(seconds = 1, intervalMs = 500, question = "q"))
    }

    @Test fun secondQuestionSeesTheFirstAnswer() = runBlocking {
        var clock = 0L
        val vlm = FakeVlm()
        val ctx = SequentialContext()
        val uc = WatchSequence(FakeSampler((0 until 20).map { frame(it * 1000L, it + 1) }), vlm, ctx, { clock }) { clock += it }
        uc(2, 1000, "first")
        uc(2, 1000, "second")
        assertTrue(vlm.last!!.context.contains("a video started"))
    }
}

class VlmEndpointPolicyTest {
    @Test fun httpsIsAlwaysAllowed() { assertTrue(VlmEndpointPolicy.isAllowed("https://vlm.example.com/v1")) }

    @Test fun plainHttpOnlyOnPrivateNetworks() {
        assertTrue(VlmEndpointPolicy.isAllowed("http://127.0.0.1:8080/v1"))
        assertTrue(VlmEndpointPolicy.isAllowed("http://192.168.1.20:8000/v1"))
        assertTrue(VlmEndpointPolicy.isAllowed("http://10.0.0.5/v1"))
        assertTrue(VlmEndpointPolicy.isAllowed("http://100.101.102.103:8000/v1"))
        assertTrue(VlmEndpointPolicy.isAllowed("http://laptop.local:11434/v1"))
        assertFalse(VlmEndpointPolicy.isAllowed("http://8.8.8.8/v1"))
        assertFalse(VlmEndpointPolicy.isAllowed("http://example.com/v1"))
    }

    @Test fun garbageIsRejected() {
        assertFalse(VlmEndpointPolicy.isAllowed("ftp://x"))
        assertFalse(VlmEndpointPolicy.isAllowed(""))
    }
}

class TrajectoryJsonlTest {
    @Test fun encodesOneStepAsValidLookingJson() {
        val s = TrajectoryJsonl.encode(TrajectoryStep("e1", 2, "open \"youtube\"", "click", "line1\nline2", true, 255L, null, 99L))
        assertTrue(s.startsWith("{") && s.endsWith("}"))
        assertTrue(s.contains("\\\"youtube\\\"") && s.contains("line1\\nline2"))
        assertTrue(s.contains("\"success\":true") && s.contains("\"screen_digest\":\"ff\"") && s.contains("\"frame\":null"))
    }

    @Test fun controlCharactersAreEscaped() {
        assertEquals("\"a\\u0001b\"", TrajectoryJsonl.quote("a\u0001b"))
    }
}

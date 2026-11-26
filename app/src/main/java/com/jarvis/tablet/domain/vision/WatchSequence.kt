package com.jarvis.tablet.domain.vision

/**
 * Use case: watch for a few seconds, keep only the frames that matter, and ask the model what happened.
 * Time and waiting are injected so the logic is testable without real clocks or devices (Dependency Inversion).
 */
class WatchSequence(
    private val sampler: FrameSampler,
    private val vlm: VisionLanguageModel,
    private val context: SequentialContext,
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit
) {
    suspend operator fun invoke(
        seconds: Int,
        intervalMs: Long,
        question: String,
        selector: KeyframeSelector = KeyframeSelector()
    ): VlmResult? {
        val end = now() + seconds.coerceIn(1, 30) * 1000L
        while (true) {
            sampler.capture()?.let { selector.offer(it) }
            if (now() >= end) break
            pause(intervalMs.coerceAtLeast(300))
        }
        val frames = selector.result()
        if (frames.isEmpty()) return null
        val result = vlm.describe(VlmRequest(frames, question, context.asPrompt(now())))
        context.add(SequentialContext.Entry(now(), frames.last().source, result.text))
        return result
    }
}

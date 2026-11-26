package com.jarvis.tablet.domain.vision

/** Port: where frames come from (screen, camera, a recording...). Implemented in the data layer. */
interface FrameSampler {
    suspend fun capture(): Frame?
}

class VlmRequest(val frames: List<Frame>, val question: String, val context: String)

class VlmResult(val text: String, val model: String)

/**
 * Port: any vision-language model. The app depends on this interface only, so a cloud model, a local open-source model
 * served over an OpenAI-compatible API, or a future on-device model can be swapped without touching the use cases.
 */
interface VisionLanguageModel {
    suspend fun describe(request: VlmRequest): VlmResult
}

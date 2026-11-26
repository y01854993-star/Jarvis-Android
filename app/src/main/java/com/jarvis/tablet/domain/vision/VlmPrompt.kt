package com.jarvis.tablet.domain.vision

/** The instruction every adapter sends with the frames, so all models are asked the same way. */
object VlmPrompt {
    fun build(request: VlmRequest): String {
        val sb = StringBuilder()
        if (request.frames.size > 1) {
            val t0 = request.frames.first().timestampMs
            val labels = request.frames.joinToString(", ") { "+" + ((it.timestampMs - t0) / 1000.0).let { s -> "%.1f".format(java.util.Locale.US, s) } + "s" }
            sb.append("These ${request.frames.size} images are keyframes of ONE continuous recording, in time order (").append(labels)
                .append("). Describe what changed between them and the final state. ")
        } else {
            sb.append("Describe what is shown. ")
        }
        if (request.context.isNotBlank()) sb.append("\nWhat was seen before:\n").append(request.context).append('\n')
        sb.append("\nQuestion: ").append(request.question.trim())
        sb.append("\nAnswer directly and briefly (1-3 short spoken sentences), focusing on the visual context. Do not invent what you cannot see.")
        return sb.toString()
    }
}

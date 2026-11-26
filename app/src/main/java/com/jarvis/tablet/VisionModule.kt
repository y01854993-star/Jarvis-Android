package com.jarvis.tablet

import android.content.Context
import com.jarvis.tablet.data.vision.BitmapFrameSampler
import com.jarvis.tablet.data.vision.ContactSheetVlm
import com.jarvis.tablet.data.vision.OpenAiCompatibleVlm
import com.jarvis.tablet.data.vision.TrajectoryStore
import com.jarvis.tablet.domain.vision.FrameSource
import com.jarvis.tablet.domain.vision.SequentialContext
import com.jarvis.tablet.domain.vision.VisionLanguageModel
import com.jarvis.tablet.domain.vision.VlmEndpointPolicy
import com.jarvis.tablet.domain.vision.WatchSequence
import kotlinx.coroutines.delay

/**
 * Composition root of the vision slice: the only place that knows which adapter implements which port.
 * Everything else depends on the interfaces in domain/vision.
 */
class VisionModule(
    ctx: Context,
    private val screenshot: suspend () -> String?,
    private val askCloud: suspend (jpeg: ByteArray, prompt: String) -> String?
) {
    val context = SequentialContext()
    val trajectories = TrajectoryStore(ctx)

    /** an open-source model on the user's own server when configured and the address is safe, otherwise the existing cloud router */
    private fun chooseModel(): VisionLanguageModel {
        val url = Keys.vlmUrl
        return if (url.isNotBlank() && VlmEndpointPolicy.isAllowed(url)) OpenAiCompatibleVlm(url, Keys.vlmModel, Keys.vlmKey)
        else ContactSheetVlm(askCloud)
    }

    fun watchSequence(): WatchSequence =
        WatchSequence(BitmapFrameSampler(FrameSource.SCREEN, screenshot), chooseModel(), context, { System.currentTimeMillis() }) { delay(it) }
}

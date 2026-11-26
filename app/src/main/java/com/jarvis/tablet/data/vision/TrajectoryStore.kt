package com.jarvis.tablet.data.vision

import android.content.Context
import android.util.Base64
import com.jarvis.tablet.domain.vision.TrajectoryJsonl
import com.jarvis.tablet.domain.vision.TrajectoryStep
import java.io.File

/**
 * Adapter: appends agent steps as JSON Lines under the app's private storage. Opt-in only. Frames are stored only when
 * the caller passes one, and the store is capped so it can never fill the phone.
 */
class TrajectoryStore(ctx: Context, private val maxBytes: Long = 8L * 1024 * 1024) {
    private val dir = File(ctx.filesDir, "trajectories").apply { mkdirs() }
    private val file = File(dir, "episodes.jsonl")

    @Synchronized
    fun record(step: TrajectoryStep, frameB64: String? = null) {
        if (file.length() > maxBytes) return
        var frameName: String? = null
        if (frameB64 != null && dir.walkTopDown().sumOf { it.length() } < maxBytes) {
            frameName = "${step.episode}_${step.index}.jpg"
            File(dir, frameName).writeBytes(Base64.decode(frameB64, Base64.DEFAULT))
        }
        val line = TrajectoryJsonl.encode(TrajectoryStep(step.episode, step.index, step.goal, step.action, step.target,
            step.success, step.screenDigest, frameName, step.timestampMs))
        file.appendText(line + "\n")
    }

    fun steps(): Int = if (file.exists()) file.useLines { it.count() } else 0

    /** text of the JSONL file (frames stay on the phone), for an explicit, confirmed export */
    fun readAll(): String = if (file.exists()) file.readText() else ""

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

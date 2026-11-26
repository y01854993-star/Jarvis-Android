package com.jarvis.tablet.domain.vision

/**
 * Chooses the few frames worth showing to a vision-language model out of a stream of captures.
 * Rules: keep a frame when the picture changed enough, or when too much time passed; never keep more than [maxFrames];
 * always finish on the newest frame so the model sees the final state.
 */
class KeyframeSelector(
    private val minDistance: Int = 6,
    private val maxGapMs: Long = 4000,
    private val maxFrames: Int = 4
) {
    init {
        require(maxFrames >= 2) { "maxFrames must be at least 2" }
    }

    private val kept = ArrayList<Frame>()
    private var newest: Frame? = null

    /** @return true when the frame was kept as a keyframe */
    fun offer(frame: Frame): Boolean {
        newest = frame
        val prev = kept.lastOrNull()
        val keep = prev == null ||
            PerceptualHash.distance(prev.digest, frame.digest) >= minDistance ||
            frame.timestampMs - prev.timestampMs >= maxGapMs
        if (!keep) return false
        kept.add(frame)
        reduce(kept)
        return true
    }

    /** keyframes in time order, ending with the newest frame seen */
    fun result(): List<Frame> {
        val last = newest ?: return emptyList()
        val out = ArrayList(kept)
        if (out.lastOrNull() !== last) out.add(last)
        reduce(out)
        return out
    }

    /** drops the middle frame that adds the least information (smallest change from its predecessor) until the budget fits */
    private fun reduce(list: MutableList<Frame>) {
        while (list.size > maxFrames) {
            var worst = 1
            var worstDist = Int.MAX_VALUE
            for (i in 1 until list.size - 1) {
                val d = PerceptualHash.distance(list[i - 1].digest, list[i].digest)
                if (d < worstDist) { worstDist = d; worst = i }
            }
            list.removeAt(worst)
        }
    }
}

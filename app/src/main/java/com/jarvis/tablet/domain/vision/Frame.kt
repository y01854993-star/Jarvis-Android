package com.jarvis.tablet.domain.vision

enum class FrameSource { SCREEN, CAMERA }

/**
 * One captured image, already downscaled and JPEG-encoded by the data layer.
 * [grid] is a 9x8 grayscale thumbnail used only for change detection (see [PerceptualHash]).
 */
class Frame(
    val timestampMs: Long,
    val source: FrameSource,
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
    val grid: IntArray
) {
    init {
        require(grid.size == PerceptualHash.GRID_W * PerceptualHash.GRID_H) { "grid must be ${PerceptualHash.GRID_W}x${PerceptualHash.GRID_H}" }
    }

    val digest: Long by lazy { PerceptualHash.dHash(grid) }
}

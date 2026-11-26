package com.jarvis.tablet.domain.vision

/**
 * Difference hash (dHash): a 64-bit fingerprint of an image that changes little when the picture changes little.
 * Cheap enough to run on every captured frame, which is what makes fast frame filtering possible.
 */
object PerceptualHash {
    const val GRID_W = 9
    const val GRID_H = 8

    /** @param grid row-major 9x8 grayscale values */
    fun dHash(grid: IntArray): Long {
        require(grid.size == GRID_W * GRID_H)
        var h = 0L
        var bit = 0
        for (y in 0 until GRID_H) {
            for (x in 0 until GRID_W - 1) {
                if (grid[y * GRID_W + x] < grid[y * GRID_W + x + 1]) h = h or (1L shl bit)
                bit++
            }
        }
        return h
    }

    /** number of differing bits, 0..64 */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}

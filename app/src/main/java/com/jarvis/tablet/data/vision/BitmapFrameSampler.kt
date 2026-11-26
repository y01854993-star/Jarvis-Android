package com.jarvis.tablet.data.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.jarvis.tablet.domain.vision.Frame
import com.jarvis.tablet.domain.vision.FrameSampler
import com.jarvis.tablet.domain.vision.FrameSource
import com.jarvis.tablet.domain.vision.PerceptualHash
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapter: turns any "give me a base64 image" function (screenshot, camera) into [Frame]s.
 * Does the fast preprocessing in one pass: downscale, JPEG re-encode, and the tiny grayscale grid for change detection.
 */
class BitmapFrameSampler(
    private val source: FrameSource,
    private val captureB64: suspend () -> String?,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxSide: Int = 768,
    private val quality: Int = 70
) : FrameSampler {

    override suspend fun capture(): Frame? {
        val b64 = captureB64() ?: return null
        return withContext(Dispatchers.Default) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            try { toFrame(bmp) } finally { bmp.recycle() }
        }
    }

    private fun toFrame(src: Bitmap): Frame {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val tiny = Bitmap.createScaledBitmap(scaled, PerceptualHash.GRID_W, PerceptualHash.GRID_H, true)
        val grid = IntArray(PerceptualHash.GRID_W * PerceptualHash.GRID_H)
        for (y in 0 until PerceptualHash.GRID_H) for (x in 0 until PerceptualHash.GRID_W) {
            val p = tiny.getPixel(x, y)
            grid[y * PerceptualHash.GRID_W + x] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val frame = Frame(now(), source, scaled.width, scaled.height, out.toByteArray(), grid)
        tiny.recycle()
        if (scaled !== src) scaled.recycle()
        return frame
    }
}

package com.jarvis.tablet.data.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.jarvis.tablet.domain.vision.Frame
import com.jarvis.tablet.domain.vision.VisionLanguageModel
import com.jarvis.tablet.domain.vision.VlmPrompt
import com.jarvis.tablet.domain.vision.VlmRequest
import com.jarvis.tablet.domain.vision.VlmResult
import java.io.ByteArrayOutputStream

/**
 * Adapter for models that accept ONE image (the existing cloud providers): the keyframes are tiled into a single
 * time-labelled "contact sheet", a common way to give an image model a short video.
 */
class ContactSheetVlm(
    private val ask: suspend (jpeg: ByteArray, prompt: String) -> String?,
    private val modelName: String = "cloud-router"
) : VisionLanguageModel {

    override suspend fun describe(request: VlmRequest): VlmResult {
        val sheet = if (request.frames.size == 1) request.frames[0].jpeg else compose(request.frames)
        val answer = ask(sheet, VlmPrompt.build(request))
        if (answer.isNullOrBlank()) throw IllegalStateException("the vision model returned no answer")
        return VlmResult(answer, modelName)
    }

    private fun compose(frames: List<Frame>): ByteArray {
        val shown = frames.take(4)
        val cols = if (shown.size <= 2) shown.size else 2
        val rows = (shown.size + cols - 1) / cols
        val tileW = 480
        val tileH = (tileW.toFloat() * shown[0].height / shown[0].width).toInt().coerceAtLeast(1)
        val sheet = Bitmap.createBitmap(cols * tileW, rows * tileH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.BLACK)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        val t0 = shown[0].timestampMs
        shown.forEachIndexed { i, f ->
            val bmp = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) ?: return@forEachIndexed
            val x = (i % cols) * tileW
            val y = (i / cols) * tileH
            canvas.drawBitmap(Bitmap.createScaledBitmap(bmp, tileW, tileH, true), x.toFloat(), y.toFloat(), null)
            canvas.drawText(String.format(java.util.Locale.US, "%d  +%.1fs", i + 1, (f.timestampMs - t0) / 1000.0), x + 10f, y + 36f, label)
            bmp.recycle()
        }
        val out = ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.JPEG, 75, out)
        sheet.recycle()
        return out.toByteArray()
    }
}

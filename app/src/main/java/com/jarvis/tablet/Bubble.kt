package com.jarvis.tablet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Floating draggable button: tap = talk to Jarvis, long-press = open the HUD. */
class Bubble(
    private val ctx: Context,
    private val onTap: () -> Unit,
    private val onLong: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BubbleView? = null
    private var lp: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val size = (62 * ctx.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 420 }
        lp = params

        val v = BubbleView(ctx)
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        var downTime = 0L
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - downTime > 700) onLong() else onTap()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, params)
            view = v
        } catch (e: Exception) {
            Bus.log("Bubble failed: ${e.javaClass.simpleName}")
            return
        }
        Bus.overlay = { json ->
            try {
                val o = JSONObject(json)
                if (o.optString("type") == "state") {
                    val m = o.optString("c").ifEmpty { "idle" }
                    v.post { v.setMode(m) }
                }
            } catch (_: Exception) {}
        }
    }

    fun hide() {
        Bus.overlay = null
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }
}

private class BubbleView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = "idle"
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun setMode(m: String) {
        mode = m
        if (m == "idle") { anim.cancel(); phase = 0f; invalidate() }
        else if (!anim.isRunning) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4 * d
        val col = when (mode) {
            "listening" -> 0xFF34D399.toInt()
            "thinking" -> 0xFFFBBF24.toInt()
            "recording" -> 0xFFF87171.toInt()
            else -> 0xFF22D3EE.toInt()
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xE6071A33.toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * d
        paint.color = col
        c.drawCircle(cx, cy, r, paint)
        val pulse = if (mode == "idle") 0f else (sin(phase * 2 * PI).toFloat() + 1f) / 2f
        paint.style = Paint.Style.FILL
        paint.color = col
        c.drawCircle(cx, cy, r * (0.30f + 0.12f * pulse), paint)
        if (mode != "idle") {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.alpha = (190 * (1f - pulse)).toInt()
            c.drawCircle(cx, cy, r * (0.5f + 0.4f * pulse), paint)
        }
        paint.alpha = 255
    }
}

package com.dotmusic.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** A slowly turning halftone matrix, echoing the app's full-screen spinning dot record. */
class CallDotView(context: Context) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var startTime = 0L
    private var attached = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        startTime = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        attached = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!attached) return
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * .39f
        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        val turn = t * .34f
        for (row in -9..9) for (column in -9..9) {
            val gx = column / 9f
            val gy = row / 9f
            val distance = gx * gx + gy * gy
            if (distance > 1f) continue
            val angle = turn + distance * .42f
            val x = (gx * cos(angle) - gy * sin(angle)) * radius
            val y = (gx * sin(angle) + gy * cos(angle)) * radius
            val pulse = .5f + .5f * sin(t * 2.1f - distance * 6f + column * .12f)
            val alpha = (38 + 160 * pulse * (1f - distance * .24f)).toInt()
            paint.color = if ((row + column) % 7 == 0) Color.rgb(102, 232, 161) else Color.WHITE
            paint.alpha = alpha
            canvas.drawCircle(cx + x, cy + y, (1.05f + 1.65f * pulse) * density, paint)
        }
        for (i in 0 until 36) {
            val angle = i * (Math.PI * 2 / 36).toFloat() - t * .55f
            val orbit = radius + 13f * density
            paint.color = if (i % 9 == 0) Color.rgb(102, 232, 161) else Color.argb(64, 240, 245, 248)
            val dotRadius = if (i % 9 == 0) 2.3f else 1.1f
            canvas.drawCircle(cx + cos(angle) * orbit, cy + sin(angle) * orbit, dotRadius * density, paint)
        }
        paint.color = Color.rgb(102, 232, 161)
        canvas.drawCircle(cx, cy, 3f * density, paint)
    }
}

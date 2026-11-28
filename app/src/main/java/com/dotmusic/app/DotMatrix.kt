package com.dotmusic.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.exp
import kotlin.math.hypot

/**
 * The dot grid the app sits on, and the only part of it that answers you: the dots swell and lean
 * towards a finger, and a ring of them lights up from whatever you press.
 *
 * At rest it draws exactly the faint grid it always has, in one pass, and it stops asking for
 * frames the moment the last ripple dies — an idle screen costs nothing.
 */
class DotMatrix(context: Context) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val step = 28f * d
    private val restR = 1.1f * d
    private val reach = 118f * d

    private var px = 0f
    private var py = 0f
    private var pull = 0f
    private var down = false

    private val rippleT = FloatArray(5)
    private val rippleS = FloatArray(5)
    private val rippleX = FloatArray(5)
    private val rippleY = FloatArray(5)
    private var nextRipple = 0

    private val anchor = IntArray(2)
    private val here = IntArray(2)

    private var t = 0f
    private var last = 0L
    private var posted = false
    private var busy = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Feed every touch the screen sees, in window coordinates. */
    fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                getLocationInWindow(here)
                pointer(ev.x - here[0], ev.y - here[1])
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    private fun pointer(x: Float, y: Float) {
        px = x
        py = y
        down = true
        if (pull < 0.02f) pull = 0.02f
        wake()
    }

    private fun release() {
        down = false
        wake()
    }

    /** A ring out from a point: a key, a row, a tab. */
    fun ripple(x: Float, y: Float) {
        val k = nextRipple
        rippleT[k] = t
        rippleS[k] = 1f
        rippleX[k] = x
        rippleY[k] = y
        nextRipple = (k + 1) % rippleT.size
        wake()
    }

    /** A ring out from the middle of [v], whatever screen it lives on. */
    fun rippleFrom(v: View) {
        if (v.width == 0 || v.height == 0) return
        getLocationInWindow(here)
        v.getLocationInWindow(anchor)
        ripple(anchor[0] - here[0] + v.width / 2f, anchor[1] - here[1] + v.height / 2f)
    }

    private fun wake() {
        busy = true
        ensure()
    }

    private fun ensure() {
        if (posted || !isAttachedToWindow) return
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        ensure()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        val dt = if (last == 0L) 0f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.1f)
        last = frameTimeNanos
        t += dt
        pull += ((if (down) 1f else 0f) - pull) * (dt * 9f).coerceIn(0f, 1f)
        busy = down || pull > 0.02f
        for (i in rippleT.indices) {
            if (rippleS[i] > 0f && t - rippleT[i] > 1.05f) rippleS[i] = 0f
            if (rippleS[i] > 0f) busy = true
        }
        invalidate()
        if (busy) ensure()
    }

    override fun onDraw(canvas: Canvas) {
        val ringReach = 30f * d
        var y = step / 2f
        while (y < height) {
            var x = step / 2f
            while (x < width) {
                var r = restR
                var a = 8f
                if (pull > 0.01f) {
                    val dx = x - px
                    val dy = y - py
                    val dist = hypot(dx, dy)
                    if (dist < reach * 2.2f) {
                        val f = exp(-(dist * dist) / (2f * reach * reach)) * pull
                        if (f > 0.004f) {
                            r += 3.6f * d * f
                            a += 170f * f
                        }
                    }
                }
                for (i in rippleT.indices) {
                    val s = rippleS[i]
                    if (s <= 0f) continue
                    val age = t - rippleT[i]
                    val front = age * 380f * d
                    val dist = hypot(x - rippleX[i], y - rippleY[i])
                    if (dist > front + ringReach * 3f) continue
                    val e = front - dist
                    val f = s * exp(-(e * e) / (2f * ringReach * ringReach)) * (1f - age / 1.05f)
                    if (f > 0.004f) {
                        r += 2.8f * d * f
                        a += 130f * f
                    }
                }
                paint.color = Color.argb(a.toInt().coerceIn(0, 255), 255, 255, 255)
                canvas.drawCircle(x, y, r, paint)
                x += step
            }
            y += step
        }
    }
}

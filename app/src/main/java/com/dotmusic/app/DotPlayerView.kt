package com.dotmusic.app

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

/** The dot player on screen: a View that runs a [DotStage] every frame while it's attached. */
class DotPlayerView(context: Context) : View(context), Choreographer.FrameCallback {
    val stage = DotStage(context).also { s ->
        s.onInvalidate = ::invalidate
        s.haptic = { performHapticFeedback(it) }
    }
    private var running = false

    var spectrum: FloatArray?
        get() = stage.spectrum
        set(value) { stage.spectrum = value }

    fun setTrack(track: Track?, direction: Int = 0) = stage.setTrack(track, direction)
    fun takeDirection() = stage.takeDirection()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val insets = rootWindowInsets
        @Suppress("DEPRECATION")
        stage.resize(w, h, insets?.systemWindowInsetTop?.toFloat() ?: 0f, insets?.systemWindowInsetBottom?.toFloat() ?: 0f)
    }

    /** Re-lay out the stage after its reserved space changed. */
    fun relayout() { if (width > 0 && height > 0) onSizeChanged(width, height, width, height); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    fun start() {
        if (running) return
        running = true
        stage.restartClock()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        stage.frame(frameTimeNanos)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) = stage.draw(canvas)

    override fun onTouchEvent(event: MotionEvent): Boolean = stage.touch(event)
}

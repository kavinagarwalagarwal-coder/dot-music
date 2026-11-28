package com.dotmusic.app

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator

/**
 * Dot's motion: everything eases out fast and settles with a little overshoot, the way the dots
 * themselves spring back. Every helper here is one view property animator, so nothing is scheduled
 * per frame and nothing outlives the view it is attached to.
 */
object Motion {
    /** The default ease-out: quick to start, calm to finish. */
    val out: Interpolator = DecelerateInterpolator(1.9f)

    /** A gentle overshoot, for things that should feel physical. */
    val spring: Interpolator = OvershootInterpolator(1.15f)

    fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density

    /**
     * A key, row or button that gives under the finger and springs back on release. The listener
     * returns false, so the view's own click and ripple behave exactly as before.
     */
    fun press(view: View, scale: Float = 0.96f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(out).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(230).setInterpolator(spring).start()
            }
            false
        }
    }

    /** Fade and rise into place, optionally late by [delay] milliseconds. */
    fun enter(view: View, delay: Long = 0, rise: Float = 14f) {
        view.alpha = 0f
        view.translationY = dp(view, rise)
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(delay)
            .setDuration(280)
            .setInterpolator(out)
            .start()
    }

    /** Stagger a list into place: each item starts just after the one before it. */
    fun enterAll(views: List<View>, step: Long = 26, rise: Float = 14f) {
        views.forEachIndexed { i, view -> enter(view, i * step, rise) }
    }

    /** One quick emphasis: a count appearing, a number changing. */
    fun pop(view: View) {
        view.animate().cancel()
        view.scaleX = 0.86f
        view.scaleY = 0.86f
        view.animate().scaleX(1f).scaleY(1f)
            .setDuration(300)
            .setInterpolator(spring)
            .start()
    }

    /** Fade and slide up into place from [fromY] pixels below. */
    fun rise(view: View, fromY: Float, duration: Long = 320) {
        view.alpha = 0f
        view.translationY = fromY
        view.animate().alpha(1f).translationY(0f)
            .setDuration(duration)
            .setInterpolator(out)
            .start()
    }
}

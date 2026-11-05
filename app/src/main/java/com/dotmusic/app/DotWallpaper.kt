package com.dotmusic.app

import android.app.WallpaperManager
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder

/**
 * The dot player as a live wallpaper: the real spinning, rippling record on the home screen.
 *
 * It only draws while visible, runs at full speed while music plays and slows to a gentle
 * 20 fps when paused. Dots move away from your finger as you swipe between home-screen pages,
 * and tapping the record plays or pauses. Skip gestures are left to the launcher.
 */
class DotWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = DotEngine()

    private inner class DotEngine : Engine(), Choreographer.FrameCallback, MusicCenter.Observer {
        private val stage = DotStage(this@DotWallpaper).also {
            it.showControls = false
            it.gestures = false
        }
        private val beat = BeatListener(this@DotWallpaper) { bands -> stage.spectrum = bands }
        private var visible = false
        private var scheduled = false
        private var frames = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            MusicCenter.attach(this@DotWallpaper)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            stage.resize(width, height, systemBar("status_bar_height"), systemBar("navigation_bar_height"))
            draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                MusicCenter.observe(this)
                beat.start()
                stage.restartClock()
                schedule()
            } else {
                MusicCenter.forget(this)
                beat.stop()
                Choreographer.getInstance().removeFrameCallback(this)
                scheduled = false
            }
        }

        override fun onDestroy() {
            onVisibilityChanged(false)
            super.onDestroy()
        }

        override fun onTrack(track: Track?) {
            stage.setTrack(track)
            schedule()
        }

        override fun onTouchEvent(event: MotionEvent) {
            stage.touch(event)
            schedule()
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) stage.tapAt(x.toFloat(), y.toFloat())
            return null
        }

        private fun schedule() {
            if (!visible || scheduled) return
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!visible) return
            // Full speed while there's music; every third frame (≈20 fps) when it's resting.
            val lively = stage.lively
            if (lively || frames++ % 3 == 0) {
                stage.frame(frameTimeNanos)
                draw()
            }
            schedule()
        }

        private fun draw() {
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try { stage.draw(canvas) } finally { runCatching { holder.unlockCanvasAndPost(canvas) } }
        }

        private fun systemBar(name: String): Float {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id != 0) resources.getDimensionPixelSize(id).toFloat() else 0f
        }
    }
}

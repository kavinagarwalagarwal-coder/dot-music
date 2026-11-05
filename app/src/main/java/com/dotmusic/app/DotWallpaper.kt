package com.dotmusic.app

import android.app.WallpaperManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.max
import kotlin.math.min

/**
 * The dot player as a live wallpaper, over your own background photo.
 *
 * The dots fade in over the photo when you want them: always, or only while music plays, and
 * can be switched off from the app or the Quick Settings tile. With the dots hidden it is just
 * your photo and draws nothing more, so it costs what a normal wallpaper costs.
 *
 * While the dots show, it runs at full speed with music and about 20 fps when resting. Dots move
 * away from your finger as you swipe between pages, and tapping the record plays or pauses.
 */
class DotWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = DotEngine()

    private inner class DotEngine : Engine(), Choreographer.FrameCallback, MusicCenter.Observer,
        SharedPreferences.OnSharedPreferenceChangeListener {
        private val app = this@DotWallpaper
        private val stage = DotStage(app).also {
            it.showControls = false
            it.gestures = false
        }
        private val beat = BeatListener(app) { bands -> stage.spectrum = bands }
        private var photo: Bitmap? = null
        private val matrix = Matrix()
        private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val scrim = Paint()
        private var visible = false
        private var scheduled = false
        private var frames = 0
        private var w = 0
        private var h = 0

        /** How much of the dot player shows over the photo, 0..1. */
        private var fade = 0f
        private var lastFrame = 0L

        private fun wanted(): Float {
            if (!DotPrefs.wallpaperDots(app)) return 0f
            if (DotPrefs.wallpaperOnlyPlaying(app) && MusicCenter.current?.isPlaying != true) return 0f
            return 1f
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            MusicCenter.attach(app)
            DotPrefs.listen(app, this)
            loadPhoto()
            fade = wanted()
        }

        override fun onDestroy() {
            onVisibilityChanged(false)
            DotPrefs.unlisten(app, this)
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key == "photo_version") loadPhoto()
            schedule()
            if (!scheduled) draw()
        }

        private fun loadPhoto() {
            photo = WallpaperPhoto.load(app)
            // Without a photo the player keeps its own black dotted backdrop.
            stage.opaque = photo == null
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            stage.resize(width, height, systemBar("status_bar_height"), systemBar("navigation_bar_height"))
            draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                MusicCenter.observe(this)
                beat.start()
                stage.restartClock()
                lastFrame = 0L
                schedule()
                draw()
            } else {
                MusicCenter.forget(this)
                beat.stop()
                Choreographer.getInstance().removeFrameCallback(this)
                scheduled = false
            }
        }

        override fun onTrack(track: Track?) {
            stage.setTrack(track)
            schedule()
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (fade < 0.5f) return
            stage.touch(event)
            schedule()
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && fade > 0.5f) stage.tapAt(x.toFloat(), y.toFloat())
            return null
        }

        /** Frames are needed only while the dots show or are fading in or out. */
        private fun needsFrames() = fade > 0f || wanted() > 0f

        private fun schedule() {
            if (!visible || scheduled || !needsFrames()) return
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!visible) return
            val dt = if (lastFrame == 0L) 1f / 60f else min(0.1f, (frameTimeNanos - lastFrame) / 1e9f)
            lastFrame = frameTimeNanos
            val target = wanted()
            fade = if (target > fade) min(target, fade + dt / 0.6f) else max(target, fade - dt / 0.45f)
            val fading = fade != target
            // Full speed with music or while fading; every third frame (≈20 fps) when resting.
            if (stage.lively || fading || frames++ % 3 == 0) {
                if (fade > 0f) stage.frame(frameTimeNanos)
                draw()
            }
            if (needsFrames()) schedule()
            else { lastFrame = 0L; draw() } // Dots gone: leave just the photo on screen.
        }

        private fun draw() {
            if (w == 0 || h == 0) return
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try { paint(canvas) } finally { runCatching { holder.unlockCanvasAndPost(canvas) } }
        }

        private fun paint(canvas: Canvas) {
            val p = photo
            if (p != null) WallpaperPhoto.drawCover(canvas, p, w, h, matrix, photoPaint) else canvas.drawColor(Color.BLACK)
            if (fade <= 0f) return
            if (p != null) {
                // Darken the photo a little so the dots stand out.
                scrim.color = Color.argb((115 * fade).toInt(), 0, 0, 0)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrim)
            }
            if (fade >= 0.999f) stage.draw(canvas)
            else {
                val layer = canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (fade * 255).toInt())
                stage.draw(canvas)
                canvas.restoreToCount(layer)
            }
        }

        private fun systemBar(name: String): Float {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id != 0) resources.getDimensionPixelSize(id).toFloat() else 0f
        }
    }
}

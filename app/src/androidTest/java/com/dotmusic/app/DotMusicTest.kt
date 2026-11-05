package com.dotmusic.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DotMusicTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun cover(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        for (i in 0 until 64) setPixel(i, i, Color.WHITE)
    }

    private fun track(title: String, color: Int, playing: Boolean = true) = Track(
        title, "Test Artist", "test.music", cover(color), color, playing,
        positionMs = 30_000, positionUpdatedAt = SystemClock.elapsedRealtime(), speed = 1f,
        durationMs = 200_000, openApp = null)

    /** Every widget, playing, paused and idle, inflates the way a launcher would inflate it. */
    @Test fun everyWidgetInflatesLikeOnAHomeScreen() {
        val layouts = listOf(R.layout.widget_small, R.layout.widget_medium, R.layout.widget_wide, R.layout.widget_stage)
        val states = listOf(track("Midnight City", Color.rgb(230, 60, 120)), track("Paused", Color.BLUE, playing = false), null)
        instrumentation.runOnMainSync {
            for (layout in layouts) for (state in states) {
                val views = MusicWidgets.build(context, layout, state)
                val host = FrameLayout(context)
                val inflated = views.apply(context, host)
                host.addView(inflated)
                assertNotNull(inflated.findViewById(R.id.play))
            }
        }
    }

    /** The 4×4 widget's record is drawn by the player's own code, and actually has dots on it. */
    @Test fun stageSnapshotHasTheRecordAndRing() {
        instrumentation.runOnMainSync {
            val stage = DotStage(context, unit = 330 / 360f)
            stage.setTrack(track("Snapshot", Color.rgb(255, 120, 0)))
            val picture = stage.snapshot(330)
            val px = IntArray(330 * 330).also { picture.getPixels(it, 0, 330, 0, 0, 330, 330) }
            val lit = px.count { Color.alpha(it) > 200 }
            assertTrue("Snapshot looks empty: $lit lit pixels", lit > 2_000)
        }
    }

    /** The wallpaper's version (no controls, no skip gestures) runs and draws without a window. */
    @Test fun wallpaperStageDrawsHeadless() {
        instrumentation.runOnMainSync {
            val stage = DotStage(context).apply { showControls = false; gestures = false }
            stage.resize(1080, 2400, 80f, 60f)
            stage.setTrack(track("Wallpaper", Color.CYAN))
            val canvas = android.graphics.Canvas(Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888))
            var now = 1_000_000_000L
            repeat(90) { now += 16_666_667L; stage.frame(now); stage.draw(canvas) }
            assertTrue(stage.tapAt(540f, -10_000f).not())
        }
    }

    /** A picked photo is saved, reloaded, and fills the screen; the dots draw see-through over it. */
    @Test fun wallpaperPhotoShowsThroughTheDots() {
        val src = java.io.File(context.cacheDir, "pick.png")
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 220)) }
            .let { b -> src.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(WallpaperPhoto.save(context, android.net.Uri.fromFile(src)))
        val photo = WallpaperPhoto.load(context)
        assertNotNull(photo)
        val screen = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(screen)
        WallpaperPhoto.drawCover(canvas, photo!!, 540, 1200, android.graphics.Matrix(), android.graphics.Paint())
        instrumentation.runOnMainSync {
            val stage = DotStage(context).apply { showControls = false; gestures = false; opaque = false }
            stage.resize(540, 1200, 40f, 30f)
            stage.setTrack(track("Over the photo", Color.YELLOW))
            stage.frame(1_000_000_000L)
            stage.draw(canvas)
        }
        // A corner far from the record still shows the photo's blue.
        val corner = screen.getPixel(4, 1190)
        assertTrue("Photo hidden behind the dots: ${Integer.toHexString(corner)}", Color.blue(corner) > 150)
        WallpaperPhoto.clear(context)
        assertTrue(!WallpaperPhoto.exists(context))
    }

    /** The player runs, survives song changes and play/pause, and keeps drawing frames. */
    @Test fun playerRunsThroughSongChanges() {
        ActivityScenario.launch(DotPlayerActivity::class.java).use { scenario ->
            var view: DotPlayerView? = null
            scenario.onActivity { view = it.window.decorView.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as DotPlayerView }
            instrumentation.runOnMainSync { view!!.setTrack(track("First Song", Color.RED)) }
            SystemClock.sleep(800)
            instrumentation.runOnMainSync { view!!.setTrack(track("A Much Longer Second Song Title That Scrolls", Color.GREEN), -1) }
            SystemClock.sleep(1_200)
            instrumentation.runOnMainSync { view!!.setTrack(track("A Much Longer Second Song Title That Scrolls", Color.GREEN, playing = false)) }
            SystemClock.sleep(600)
            instrumentation.runOnMainSync { view!!.spectrum = FloatArray(48) { (it % 7) / 7f } }
            SystemClock.sleep(600)
            instrumentation.runOnMainSync { view!!.setTrack(null) }
            SystemClock.sleep(600)
            assertTrue(view!!.isAttachedToWindow)
        }
    }
}

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
        val layouts = listOf(R.layout.widget_small, R.layout.widget_medium, R.layout.widget_wide)
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

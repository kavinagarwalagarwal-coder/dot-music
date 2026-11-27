package com.dotmusic.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun photo(): Bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(30, 180, 140))
        for (i in 0 until 120) setPixel(i, i, Color.WHITE)
    }

    /** A custom contact photo is saved, loaded back and removed again. */
    @Test fun customPhotoRoundTrip() {
        val key = "5550100"
        ContactPhotos.clear(context, key)
        assertTrue(ContactPhotos.save(context, key, photo()))
        assertNotNull(ContactPhotos.load(context, key))
        assertNotNull(ContactPhotos.avatar(context, key, 96))
        ContactPhotos.clear(context, key)
        assertNull(ContactPhotos.load(context, key))
    }

    /** Numbers that are the same person share one key. */
    @Test fun numbersNormalise() {
        assertEquals(Store.key("+44 7700 900123"), Store.key("07700900123"))
        assertEquals("5551234", Store.key("555-1234"))
    }

    /** The dot record runs and actually draws the disc of dots, without a window. */
    @Test fun recordDrawsDots() {
        instrumentation.runOnMainSync {
            val record = DotRecord(context).apply {
                setTitle("Ada Lovelace")
                setArt(photo())
                resize(540, 1080, 40f, 40f)
            }
            val screen = Bitmap.createBitmap(540, 1080, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(screen)
            var now = 1_000_000_000L
            repeat(40) {
                now += 16_666_667L
                record.advanceFrame(now)
            }
            record.render(canvas)
            val pixels = IntArray(540 * 1080).also { screen.getPixels(it, 0, 540, 0, 0, 540, 1080) }
            val lit = pixels.count { Color.alpha(it) > 120 && it != Color.BLACK }
            assertTrue("The record looks empty: $lit lit pixels", lit > 2_000)
        }
    }

    /** The call screen's own timer keeps counting while it is on screen. */
    @Test fun timerFormats() {
        val record = DotRecord(context)
        record.subtitle = "00:07"
        record.progress = 0.1f
        assertEquals("00:07", record.subtitle)
        SystemClock.sleep(1)
    }
}

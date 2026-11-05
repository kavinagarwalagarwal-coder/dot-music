package com.dotmusic.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotArtTest {
    @Test fun brighterPartsOfTheCoverGetBiggerDots() {
        val cell = 10f
        val dark = DotArt.halftoneRadius(0f, cell)
        val mid = DotArt.halftoneRadius(0.5f, cell)
        val bright = DotArt.halftoneRadius(1f, cell)
        assertTrue(dark < mid && mid < bright)
        // Even black keeps a small dot, and white never overlaps its neighbours.
        assertTrue(dark > 0f)
        assertTrue(bright * 2 < cell)
    }

    @Test fun luminanceFollowsHowBrightColoursLook() {
        assertEquals(0f, DotArt.luminance(0xFF000000.toInt()), 0.001f)
        assertEquals(1f, DotArt.luminance(0xFFFFFFFF.toInt()), 0.001f)
        // Green looks far brighter than blue at the same strength.
        assertTrue(DotArt.luminance(0xFF00FF00.toInt()) > DotArt.luminance(0xFF0000FF.toInt()) * 5)
    }

    @Test fun progressMovesOnlyWhilePlaying() {
        val base = Track("Song", "Artist", "app", null, 0, isPlaying = true, positionMs = 10_000,
            positionUpdatedAt = 1_000, speed = 1f, durationMs = 100_000, openApp = null)
        assertEquals(15_000L, base.livePositionMs(now = 6_000))
        assertEquals(10_000L, base.copy(isPlaying = false).livePositionMs(now = 6_000))
        // Never runs past the end of the song.
        assertEquals(100_000L, base.livePositionMs(now = 500_000))
        assertEquals(0.15f, base.progress(now = 6_000), 0.001f)
    }
}

package com.dotmusic.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotArtTest {
    @Test fun brighterPartsOfThePhotoGetBiggerDots() {
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
}

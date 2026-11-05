package com.dotmusic.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns album art and text into dot-matrix pictures, the way the Nothing dot font draws letters.
 *
 * Everything here runs once per song (never per frame) and produces small bitmaps that are cheap
 * to hand to a home-screen widget.
 */
object DotArt {
    /** Halftone: brighter parts of the cover get bigger dots. Pure, unit tested. */
    fun halftoneRadius(luminance: Float, cell: Float): Float =
        cell * (MIN_DOT + (MAX_DOT - MIN_DOT) * luminance.coerceIn(0f, 1f).pow(0.8f))

    /** Perceived brightness, 0..1. */
    fun luminance(color: Int): Float =
        (0.2126f * ((color shr 16) and 0xFF) + 0.7152f * ((color shr 8) and 0xFF) + 0.0722f * (color and 0xFF)) / 255f

    /** A touch more colour, so small dots on black still read as the cover's palette. */
    fun vivid(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = min(1f, hsv[1] * 1.25f)
        hsv[2] = max(0.35f, hsv[2])
        return Color.HSVToColor(hsv)
    }

    /** The cover averaged down to an [n]×[n] grid of colours (filtered, so it's a true average). */
    fun sample(src: Bitmap, n: Int): IntArray {
        val small = Bitmap.createScaledBitmap(src, n, n, true)
        val out = IntArray(n * n)
        small.getPixels(out, 0, n, 0, 0, n, n)
        if (small !== src) small.recycle()
        return out
    }

    /**
     * The cover as a [n]×[n] halftone of round dots on transparent, [size] pixels square.
     * [mono] draws it in white like Nothing's glyphs; otherwise each dot keeps its colour.
     * [disc] keeps only the dots inside a circle, with a red spindle in the middle, like a record.
     */
    fun cover(src: Bitmap?, n: Int, size: Int, mono: Boolean, disc: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell = size.toFloat() / n
        val colors = src?.let { sample(it, n) }
        val half = n / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x + .5f - half
            val dy = y + .5f - half
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (disc && (d > half - .2f || d < n * SPINDLE)) continue
            val c = colors?.get(y * n + x) ?: Color.DKGRAY
            val lum = if (colors == null) 0.25f else luminance(c)
            paint.color = if (mono) Color.argb((90 + 165 * lum).roundToInt(), 255, 255, 255) else vivid(c)
            canvas.drawCircle((x + .5f) * cell, (y + .5f) * cell, halftoneRadius(lum, cell), paint)
        }
        if (disc) {
            paint.color = SPINDLE_RED
            canvas.drawCircle(size / 2f, size / 2f, cell * 0.55f, paint)
        }
        return out
    }

    /**
     * [text] set in dots: drawn tiny ([rows] pixels tall) and each lit pixel becomes a dot. Works
     * for any script, since it uses the system font rather than a fixed dot alphabet.
     */
    fun text(text: String, rows: Int, dot: Float, color: Int, maxColumns: Int): Bitmap {
        val probe = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = rows * 1.18f
            isAntiAlias = false
        }
        var s = text.trim().ifEmpty { " " }
        // Keep it within the widget: shorten with an ellipsis rather than squashing the dots.
        while (s.length > 1 && probe.measureText(s) > maxColumns) s = s.dropLast(2).trimEnd() + "…"
        val cols = max(1, min(maxColumns, probe.measureText(s).roundToInt() + 1))
        val mask = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawText(s, 0f, rows - probe.descent() * 0.55f, probe.apply { this.color = Color.WHITE })
        val px = IntArray(cols * rows)
        mask.getPixels(px, 0, cols, 0, 0, cols, rows)
        mask.recycle()
        val pitch = dot * 1.5f
        val out = Bitmap.createBitmap((cols * pitch).roundToInt().coerceAtLeast(1), (rows * pitch).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val unlit = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(26, 255, 255, 255) }
        for (y in 0 until rows) for (x in 0 until cols) {
            val on = Color.alpha(px[y * cols + x]) > 100
            canvas.drawCircle((x + .5f) * pitch, (y + .5f) * pitch, dot / 2f, if (on) lit else unlit)
        }
        return out
    }

    /** A row of [count] dots: played ones lit in [accent], the current one larger. */
    fun progress(fraction: Float, count: Int, dot: Float, accent: Int): Bitmap {
        val pitch = dot * 1.9f
        val out = Bitmap.createBitmap((count * pitch).roundToInt(), (dot * 2f).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val head = (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
        for (i in 0 until count) {
            paint.color = when {
                i < head -> accent
                i == head -> Color.WHITE
                else -> Color.argb(46, 255, 255, 255)
            }
            canvas.drawCircle((i + .5f) * pitch, dot, if (i == head) dot * 0.95f else dot * 0.5f, paint)
        }
        return out
    }

    private const val SPINDLE = 0.07f
    const val SPINDLE_RED = 0xFFFF3B30.toInt()
    private const val MIN_DOT = 0.12f
    private const val MAX_DOT = 0.47f
}

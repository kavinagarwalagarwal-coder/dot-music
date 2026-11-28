package com.dotmusic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The dot record: a photo turned into a spinning disc of hundreds of springy dots, with an
 * equaliser ring and the name set in dots underneath.
 *
 * This is the same picture the app has always drawn, now used for people: a contact's photo becomes
 * the record, the ring breathes while the line is live, and the row of dots underneath counts the
 * call. No gestures, no media controls: the host owns everything around it.
 */
class DotRecord(context: Context) : View(context), Choreographer.FrameCallback {
    /** Draw the name in dots. */
    var showTitle = true
    /** Draw the subtitle line and the row of dots under it. */
    var showInfo = true
    /** White dots, Nothing style, instead of the photo's own colours. */
    var mono = false
        set(value) {
            if (field == value) return
            field = value
            art?.let { loadCover(it, animate = false) }
            invalidate()
        }
    /** Spinning and rippling. Off while a call is on hold. */
    var playing = true
        set(value) {
            field = value
            invalidate()
        }
    var accent = Glass.ACCENT
        set(value) {
            field = value
            glowColor = 0
            invalidate()
        }
    /** The line under the title: the call state and timer. */
    var subtitle: String? = null
    /** 0..1, the row of dots under the subtitle. */
    var progress = 0f
    var topReserve = 0f
    var bottomReserve = 0f

    private var art: Bitmap? = null
    private var width = 0
    private var height = 0
    private var running = false
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // ---------------------------------------------------------------- the record

    private val n = 31
    private val count = n * n
    private val alive = BooleanArray(count)
    private val homeR = FloatArray(count)
    private val homeA = FloatArray(count)
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val lumFrom = FloatArray(count)
    private val lumTo = FloatArray(count)
    private val colFrom = IntArray(count)
    private val colTo = IntArray(count)
    private var mix = 1f

    private var cx = 0f
    private var cy = 0f
    private var cell = 0f
    private var discRadius = 0f
    private var spin = 0f
    private var spinSpeed = 0f

    // ---------------------------------------------------------------- ring equaliser

    private val bands = 48
    private val ring = FloatArray(bands)

    // ---------------------------------------------------------------- ripples

    private val rippleT = FloatArray(6)
    private val rippleS = FloatArray(6)
    private val rippleX = FloatArray(6)
    private val rippleY = FloatArray(6)
    private var nextRipple = 0
    private var lastBeat = 0f
    private var beatGlow = 0f

    // ---------------------------------------------------------------- name in dots

    private var titleMask = BooleanArray(0)
    private var titleCols = 0
    private val titleRows = 11
    private var titleBorn = 0f
    private var marquee = 0f

    // ---------------------------------------------------------------- drawing

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowColor = 0
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = runCatching {
            Typeface.Builder(context.assets, "fonts/Doto.ttf")
                .setFontVariationSettings("'wght' 800, 'ROND' 100").build()
        }.getOrDefault(Typeface.MONOSPACE)
    }

    private var last = 0L
    private var t = 0f
    private var titleTop = 0f
    private var infoY = 0f
    private var barY = 0f
    private var barLeft = 0f
    private var barRight = 0f
    private var topInset = 0f
    private var bottomInset = 0f

    init {
        loadCover(demoCover(), animate = false)
        buildTitle("")
    }

    // ---------------------------------------------------------------- public API

    fun setArt(bitmap: Bitmap?, animate: Boolean = true) {
        art = bitmap
        loadCover(bitmap ?: demoCover(), animate)
    }

    fun setTitle(text: String) {
        if (text == shownTitle) return
        shownTitle = text
        buildTitle(text)
        invalidate()
    }

    private var shownTitle = ""

    /** Re-lay out after the space reserved above or below changed. */
    fun relayout() {
        if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val insets = rootWindowInsets
        @Suppress("DEPRECATION")
        val top = insets?.systemWindowInsetTop?.toFloat() ?: 0f
        @Suppress("DEPRECATION")
        val bottom = insets?.systemWindowInsetBottom?.toFloat() ?: 0f
        resize(w, h, top, bottom)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        advanceFrame(frameTimeNanos)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Advance one frame without a window, for hosts that draw their own canvas. */
    fun advanceFrame(frameTimeNanos: Long) {
        val dt = if (last == 0L) 1f / 60f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 1f / 20f)
        last = frameTimeNanos
        t += dt
        step(dt)
    }

    // ---------------------------------------------------------------- layout

    fun resize(w: Int, h: Int, top: Float, bottom: Float) {
        width = w
        height = h
        topInset = top + topReserve
        bottomInset = bottom + bottomReserve
        val usable = max(1f, h - topInset - bottomInset)
        val compact = !showTitle && !showInfo
        val size = if (compact) min(w, h).toFloat() * 0.82f else min(w - dp(76f), usable * 0.52f)
        discRadius = size / 2f
        cell = size / n
        cx = w / 2f
        if (compact) {
            cy = h / 2f
            titleTop = cy + discRadius
            infoY = titleTop
            barY = titleTop
        } else {
            val titleH = titleRows * pitch()
            val stack = size + (if (showTitle) dp(38f) + titleH + dp(26f) else 0f) +
                (if (showInfo) dp(30f) + dp(22f) else 0f)
            val topY = topInset + max(dp(8f), (usable - stack) / 2f)
            cy = topY + discRadius
            titleTop = cy + discRadius + dp(38f)
            infoY = titleTop + titleH + dp(26f)
            barY = infoY + dp(30f)
        }
        barLeft = dp(46f)
        barRight = w - dp(46f)
        label.textSize = dp(15f)
        for (i in 0 until count) if (alive[i]) placeHome(i)?.let { (hx, hy) -> x[i] = hx; y[i] = hy }
    }

    private fun pitch() = max(5f, min(width.toFloat() - dp(64f), 999f) / 70f)

    private fun placeHome(i: Int): Pair<Float, Float>? {
        if (!alive[i]) return null
        val a = homeA[i] + spin
        val r = homeR[i] * cell
        return Pair(cx + r * cos(a), cy + r * sin(a))
    }

    // ---------------------------------------------------------------- photo to dots

    private fun loadCover(src: Bitmap, animate: Boolean) {
        val sampled = DotArt.sample(src, n)
        val half = n / 2f
        for (yy in 0 until n) for (xx in 0 until n) {
            val i = yy * n + xx
            val dx = xx + .5f - half
            val dy = yy + .5f - half
            val dist = sqrt(dx * dx + dy * dy)
            alive[i] = dist <= half - .2f && dist >= n * 0.07f
            homeR[i] = dist
            homeA[i] = atan2(dy, dx)
            val c = sampled[i]
            val lum = DotArt.luminance(c)
            val color = if (mono) Color.WHITE else DotArt.vivid(c)
            colFrom[i] = if (animate) blend(colFrom[i], colTo[i], ease(mix)) else color
            lumFrom[i] = if (animate) lumFrom[i] + (lumTo[i] - lumFrom[i]) * ease(mix) else lum
            colTo[i] = color
            lumTo[i] = lum
        }
        mix = if (animate) 0f else 1f
    }

    /** Nothing chosen yet: a soft red-to-white gradient record, so the screen still looks alive. */
    private fun demoCover(): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 64f, 64f,
                intArrayOf(0xFFFF3B30.toInt(), 0xFF1C070A.toInt(), 0xFFF8EEEE.toInt()),
                null, Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, 64f, 64f, p)
        return b
    }

    private fun buildTitle(text: String) {
        val probe = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = titleRows * 1.18f
            isAntiAlias = false
        }
        val s = text.uppercase().trim().ifEmpty { " " }
        val cols = max(1, (probe.measureText(s) + 2).roundToInt())
        val mask = Bitmap.createBitmap(cols, titleRows, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawText(s, 0f, titleRows - probe.descent() * 0.55f, probe.apply { color = Color.WHITE })
        val px = IntArray(cols * titleRows)
        mask.getPixels(px, 0, cols, 0, 0, cols, titleRows)
        mask.recycle()
        titleMask = BooleanArray(px.size) { Color.alpha(px[it]) > 100 }
        titleCols = cols
        titleBorn = t
        marquee = 0f
    }

    // ---------------------------------------------------------------- motion

    private fun ripple(x0: Float, y0: Float, strength: Float) {
        val k = nextRipple
        rippleT[k] = t
        rippleS[k] = strength
        rippleX[k] = x0
        rippleY[k] = y0
        nextRipple = (k + 1) % rippleT.size
        beatGlow = max(beatGlow, strength)
    }

    /** A steady 116 bpm pulse: a call has no audio spectrum to follow, and shouldn't have one. */
    private fun listen(dt: Float) {
        val period = 60f / 116f
        if (playing && t - lastBeat > period) {
            lastBeat = t
            val bar = ((t / period).roundToInt() % 4) == 0
            ripple(cx, cy, if (bar) 1f else 0.55f)
        }
        for (b in 0 until bands) {
            val a = b * 2f * PI.toFloat() / bands
            val v = if (!playing) 0.04f else (0.30f + 0.20f * sin(t * 2.3f + a * 3f) +
                0.16f * sin(t * 3.7f - a * 5f) + 0.24f * exp(-((t - lastBeat) * 6f))).coerceIn(0f, 1f)
            ring[b] += (v - ring[b]) * min(1f, dt * 10f)
        }
        beatGlow = max(0f, beatGlow - dt * 2.2f)
    }

    private fun step(dt: Float) {
        val targetSpin = if (playing) (2f * PI.toFloat() / 18f) else 0f
        spinSpeed += (targetSpin - spinSpeed) * min(1f, dt * 1.5f)
        spin += spinSpeed * dt
        if (mix < 1f) mix = min(1f, mix + dt / 0.9f)
        listen(dt)

        val k = 210f
        val damp = 2f * sqrt(k) * 0.62f
        for (i in 0 until count) {
            if (!alive[i]) continue
            val a = homeA[i] + spin
            val r = homeR[i] * cell
            var hx = cx + r * cos(a)
            var hy = cy + r * sin(a)
            for (j in rippleT.indices) {
                val age = t - rippleT[j]
                if (rippleS[j] <= 0f || age > 1.6f) continue
                val dx = hx - rippleX[j]
                val dy = hy - rippleY[j]
                val dist = hypot(dx, dy) + 0.01f
                val front = age * 520f * d - dist
                val e = rippleS[j] * exp(-(front * front) / (2f * (34f * d) * (34f * d))) * (1f - age / 1.6f)
                hx += dx / dist * e * 9f * d
                hy += dy / dist * e * 9f * d
            }
            val ax = (hx - x[i]) * k - vx[i] * damp
            val ay = (hy - y[i]) * k - vy[i] * damp
            vx[i] += ax * dt
            vy[i] += ay * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
        if (showTitle) {
            val titleW = titleCols * pitch()
            if (titleW > width - dp(48f) && t - titleBorn > 2.5f) marquee += dt * 34f * d
        }
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        render(canvas)
    }

    /** Draw one frame into any canvas: the view's own draw, and the headless tests. */
    fun render(canvas: Canvas) {
        drawGlow(canvas)
        drawRing(canvas)
        drawRecord(canvas)
        if (showTitle) drawTitle(canvas)
        if (showInfo) drawInfo(canvas)
    }

    private fun drawGlow(canvas: Canvas) {
        if (glowColor != accent || glow.shader == null) {
            glowColor = accent
            glow.shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Glass.withAlpha(accent, 90), Glass.withAlpha(accent, 0)),
                null, Shader.TileMode.CLAMP
            )
        }
        val rad = discRadius * (1.45f + 0.12f * beatGlow)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(rad, rad)
        glow.alpha = ((if (playing) 0.5f else 0.22f) * 255 * (0.7f + 0.3f * beatGlow)).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, 1f, glow)
        canvas.restore()
    }

    private fun drawRing(canvas: Canvas) {
        val base = discRadius + dp(18f)
        for (b in 0 until bands) {
            val a = b * 2f * PI.toFloat() / bands - PI.toFloat() / 2 + spin * 0.25f
            val level = ring[b]
            val stack = 1 + (level * 4f).roundToInt()
            for (s in 0 until stack) {
                val rr = base + s * dp(7f)
                dot.color = if (s == stack - 1 && level > 0.62f) accent else Color.WHITE
                dot.alpha = ((if (playing) 0.8f else 0.26f) * 255 * (1f - s * 0.16f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx + rr * cos(a), cy + rr * sin(a), dp(2.2f) - s * dp(0.25f), dot)
            }
        }
    }

    private fun drawRecord(canvas: Canvas) {
        val m = ease(mix)
        val dim = if (playing) 1f else 0.6f
        for (i in 0 until count) {
            if (!alive[i]) continue
            val lum = lumFrom[i] + (lumTo[i] - lumFrom[i]) * m
            var r = DotArt.halftoneRadius(lum, cell)
            r *= 1f + (if (playing) 0.07f else 0.02f) * sin(t * 1.8f - homeR[i] * 0.42f)
            for (j in rippleT.indices) {
                val age = t - rippleT[j]
                if (rippleS[j] <= 0f || age > 1.6f) continue
                val dist = hypot(x[i] - rippleX[j], y[i] - rippleY[j])
                val front = age * 520f * d - dist
                r *= 1f + 0.55f * rippleS[j] * exp(-(front * front) / (2f * (30f * d) * (30f * d))) * (1f - age / 1.6f)
            }
            val speed = kotlin.math.abs(vx[i]) + kotlin.math.abs(vy[i])
            val c = blend(colFrom[i], colTo[i], m)
            dot.color = if (speed > 900f * d) blend(c, Color.WHITE, 0.35f) else c
            dot.alpha = (255 * dim).toInt()
            canvas.drawCircle(x[i], y[i], r, dot)
        }
        dot.color = DotArt.SPINDLE_RED
        canvas.drawCircle(cx, cy, cell * (0.62f + 0.25f * beatGlow), dot)
    }

    private fun drawTitle(canvas: Canvas) {
        val p = pitch()
        val w = titleCols * p
        val scrolls = w > width - dp(48f)
        val gap = dp(60f)
        val start = if (scrolls) dp(24f) - (marquee % (w + gap)) else (width - w) / 2f
        val age = t - titleBorn
        val copies = if (scrolls) 2 else 1
        for (copy in 0 until copies) {
            val left = start + copy * (w + gap)
            for (cX in 0 until titleCols) {
                val xx = left + (cX + .5f) * p
                if (xx < -p || xx > width + p) continue
                val local = ((age - cX * 0.012f) / 0.45f).coerceIn(0f, 1f)
                val drop = (1f - ease(local)) * -dp(40f)
                for (r in 0 until titleRows) {
                    val on = titleMask[r * titleCols + cX]
                    dot.color = if (on) Color.WHITE else Color.argb(22, 255, 255, 255)
                    if (on) dot.alpha = (255 * local).toInt()
                    canvas.drawCircle(xx, titleTop + (r + .5f) * p + drop, if (on) p * 0.4f else p * 0.22f, dot)
                }
            }
        }
    }

    private fun drawInfo(canvas: Canvas) {
        canvas.drawText((subtitle ?: "").uppercase(), width / 2f, infoY, label)
        val dots = 34
        val head = (progress.coerceIn(0f, 1f) * (dots - 1)).roundToInt()
        val stepX = (barRight - barLeft) / (dots - 1)
        for (i in 0 until dots) {
            val xx = barLeft + i * stepX
            when {
                i < head -> { dot.color = accent; canvas.drawCircle(xx, barY, dp(2.6f), dot) }
                i == head -> { dot.color = Color.WHITE; canvas.drawCircle(xx, barY, dp(4.4f) + dp(1.6f) * beatGlow, dot) }
                else -> { dot.color = Color.argb(46, 255, 255, 255); canvas.drawCircle(xx, barY, dp(2.2f), dot) }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun ease(v: Float) = 1f - (1f - v) * (1f - v) * (1f - v)

    private fun blend(a: Int, b: Int, f: Float): Int {
        if (f <= 0f) return a
        if (f >= 1f) return b
        return Glass.blend(a, b, f)
    }
}

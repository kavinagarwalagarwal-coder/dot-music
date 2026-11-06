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
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.abs
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
 * The dot player's picture and physics, shared by the app screen ([DotPlayerView]) and the live
 * wallpaper ([DotWallpaper]). The host owns the frame loop and the canvas; this owns everything else.
 *
 * The full-screen dot player.
 *
 * The cover is a spinning record of springy dots. Each dot's size follows the cover's brightness
 * (a halftone), and every dot is its own little spring, so the record can breathe, ripple on the
 * beat, get pushed around by your finger, and burst apart and rebuild itself when the song changes.
 * Around it, a ring of dots works as a circular equaliser.
 *
 * Everything lives in flat float arrays and one Paint: no allocation per frame.
 */
class DotStage(private val context: Context, unit: Float? = null) {
    /** Controls drawn under the progress bar (off on the wallpaper, where the dock sits). */
    var showControls = true
    /** Flick to skip and tap to pause (off on the wallpaper: those gestures belong to the launcher). */
    var gestures = true
    /** Space kept free above and below for the host's own bars (the player's top bar and next-pick bar). */
    var topReserve = 0f
    var bottomReserve = 0f
    /** Paint its own black background (off on the wallpaper, which draws the photo underneath). */
    var opaque = true
    /** Host hooks: redraw, and a haptic tick (no-op on the wallpaper). */
    var onInvalidate: () -> Unit = {}
    var haptic: (Int) -> Unit = {}
    private val resources get() = context.resources
    private var width = 0
    private var height = 0
    private fun invalidate() = onInvalidate()
    private fun performHapticFeedback(type: Int) { haptic(type) }


    // ---------------------------------------------------------------- music

    var track: Track? = null
        private set
    private var demo = true

    /** Real loudness per band from the visualizer (0..1), or null for the built-in pulse. */
    @Volatile var spectrum: FloatArray? = null

    fun setTrack(next: Track?, direction: Int = 0) {
        val before = track
        track = next
        val songChanged = before?.id != next?.id || (before?.art !== next?.art && next?.art != null)
        if (songChanged || colors == null) {
            loadCover(next?.art ?: demoCover(), animate = before != null || !demo)
            buildTitle(next?.title ?: context.getString(R.string.player_idle))
            if (before != null && !reduceMotion()) scatter(direction)
        }
        demo = next == null
        accent = next?.accent ?: MusicListener.DEFAULT_ACCENT
        if (before?.isPlaying != true && next?.isPlaying == true) ripple(cx, cy, 1.2f)
        invalidate()
    }

    // ---------------------------------------------------------------- the record

    private val n = 31
    private val count = n * n
    private val alive = BooleanArray(count)
    private val homeR = FloatArray(count)     // distance from centre, in cells
    private val homeA = FloatArray(count)     // angle around centre
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val lumFrom = FloatArray(count)
    private val lumTo = FloatArray(count)
    private val colFrom = IntArray(count)
    private val colTo = IntArray(count)
    private var colors: IntArray? = null
    private var mix = 1f                       // 0 → 1 while one cover turns into the next

    private var cx = 0f
    private var cy = 0f
    private var cell = 0f
    private var discRadius = 0f
    private var spin = 0f                       // record angle
    private var spinSpeed = 0f                  // radians per second, eases to target
    private var accent = MusicListener.DEFAULT_ACCENT

    // ---------------------------------------------------------------- ring equaliser

    private val bands = 48
    private val ring = FloatArray(bands)        // eased band levels

    // ---------------------------------------------------------------- ripples

    private val rippleT = FloatArray(6)
    private val rippleS = FloatArray(6)
    private val rippleX = FloatArray(6)
    private val rippleY = FloatArray(6)
    private var nextRipple = 0
    private var lastBeat = 0f
    private var energyAvg = 0f
    private var beatGlow = 0f

    // ---------------------------------------------------------------- title in dots

    private var titleMask = BooleanArray(0)
    private var titleCols = 0
    private val titleRows = 11
    private var titleBorn = 0f
    private var marquee = 0f

    // ---------------------------------------------------------------- touch

    private var touching = false
    private var touchX = 0f
    private var touchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var seeking = false
    private var seekFraction = 0f
    private var lastDirection = 0

    // ---------------------------------------------------------------- drawing

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowColor = 0
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255) }
    private val doto: Typeface = runCatching {
        Typeface.Builder(context.assets, "fonts/Doto.ttf").setFontVariationSettings("'wght' 800, 'ROND' 100").build()
    }.getOrDefault(Typeface.MONOSPACE)
    /** One "dp". Snapshots for widgets use their own scale. */
    private val d = unit ?: resources.displayMetrics.density

    private var last = 0L
    private var t = 0f
    private var titleTop = 0f
    private var artistY = 0f
    private var barY = 0f
    private var barLeft = 0f
    private var barRight = 0f
    private var controlsY = 0f
    private var topInset = 0f
    private var bottomInset = 0f

    init {
        label.typeface = doto
        small.typeface = doto
        setTrack(null)
    }

    // ---------------------------------------------------------------- layout

    /** New canvas size (and system bar insets, so nothing hides behind them). */
    fun resize(w: Int, h: Int, top: Float, bottom: Float) {
        width = w; height = h
        topInset = top + topReserve
        bottomInset = bottom + bottomReserve
        val usable = h - topInset - bottomInset
        val size = min(w - 56 * d, usable * 0.5f)
        discRadius = size / 2f
        cell = size / n
        // Stack: record, title, artist, progress, controls, centred in the usable height.
        val titleH = titleRows * pitch()
        val stack = size + 36 * d + titleH + 34 * d + 44 * d + if (showControls) 84 * d else 20 * d
        val top = topInset + max(24 * d, (usable - stack) / 2f)
        cx = w / 2f
        cy = top + discRadius
        titleTop = cy + discRadius + 36 * d
        artistY = titleTop + titleH + 26 * d
        barY = artistY + 36 * d
        barLeft = 40 * d
        barRight = w - 40 * d
        controlsY = barY + 64 * d
        label.textSize = 15 * d
        small.textSize = 12 * d
        for (i in 0 until count) if (alive[i]) placeHome(i)?.let { (hx, hy) -> x[i] = hx; y[i] = hy }
    }

    private fun pitch() = max(5f, min(width.toFloat() - 64 * d, 999f) / 70f)

    private fun placeHome(i: Int): Pair<Float, Float>? {
        if (!alive[i]) return null
        val a = homeA[i] + spin
        val r = homeR[i] * cell
        return Pair(cx + r * cos(a), cy + r * sin(a))
    }

    // ---------------------------------------------------------------- covers

    private fun loadCover(src: Bitmap, animate: Boolean) {
        val sampled = DotArt.sample(src, n)
        val mono = DotPrefs.mono(context)
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
            colFrom[i] = if (animate) currentColor(i) else color
            lumFrom[i] = if (animate) currentLum(i) else lum
            colTo[i] = color
            lumTo[i] = lum
        }
        colors = sampled
        mix = if (animate) 0f else 1f
    }

    private fun currentColor(i: Int) = blend(colFrom[i], colTo[i], ease(mix))
    private fun currentLum(i: Int) = lumFrom[i] + (lumTo[i] - lumFrom[i]) * ease(mix)

    /** When nothing plays: a soft red-to-white gradient record, so the player still looks alive. */
    private fun demoCover(): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 64f, 64f, intArrayOf(0xFFFF3B30.toInt(), 0xFF2A2A2A.toInt(), 0xFFFFFFFF.toInt()), null, Shader.TileMode.CLAMP)
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

    // ---------------------------------------------------------------- effects

    /** The record bursts outward (pushed along the swipe), then each dot springs home. */
    private fun scatter(direction: Int) {
        for (i in 0 until count) {
            if (!alive[i]) continue
            val a = homeA[i] + spin
            val power = (380f + 900f * rand(i, 1)) * d
            vx[i] += cos(a) * power + direction * 700f * d * rand(i, 2)
            vy[i] += sin(a) * power - 200f * d * rand(i, 3)
        }
    }

    /** A quick shove the way the record was flicked, before the next song's cover arrives. */
    private fun nudge(direction: Int) {
        for (i in 0 until count) if (alive[i]) vx[i] += direction * (500f + 500f * rand(i, 4)) * d
    }

    private fun ripple(x0: Float, y0: Float, strength: Float) {
        if (reduceMotion()) return
        val k = nextRipple
        rippleT[k] = t; rippleS[k] = strength; rippleX[k] = x0; rippleY[k] = y0
        nextRipple = (k + 1) % rippleT.size
        beatGlow = max(beatGlow, strength)
    }

    /** Beats: from the real spectrum when allowed, otherwise a steady 116 bpm pulse while playing. */
    private fun listen(dt: Float) {
        val playing = track?.isPlaying == true || demo
        val real = spectrum
        if (real != null && playing) {
            var bass = 0f
            for (b in 0 until 6) bass += real[b]
            bass /= 6f
            energyAvg += (bass - energyAvg) * min(1f, dt * 1.6f)
            if (bass > energyAvg * 1.35f && bass > 0.18f && t - lastBeat > 0.22f) {
                lastBeat = t
                ripple(cx, cy, (0.5f + (bass - energyAvg) * 2.2f).coerceIn(0.4f, 1.3f))
            }
            for (b in 0 until bands) {
                val v = real[min(b, real.size - 1)]
                ring[b] += (v - ring[b]) * min(1f, dt * if (v > ring[b]) 22f else 7f)
            }
        } else {
            val period = 60f / 116f
            if (playing && t - lastBeat > period) {
                lastBeat = t
                val bar = ((t / period).roundToInt() % 4) == 0
                ripple(cx, cy, if (bar) 1.0f else 0.55f)
            }
            for (b in 0 until bands) {
                val a = b * 2f * PI.toFloat() / bands
                val v = if (!playing) 0.04f else (0.28f + 0.22f * sin(t * 2.3f + a * 3f) + 0.18f * sin(t * 3.7f - a * 5f) +
                    0.25f * exp(-((t - lastBeat) * 6f))).coerceIn(0f, 1f)
                ring[b] += (v - ring[b]) * min(1f, dt * 10f)
            }
        }
        beatGlow = max(0f, beatGlow - dt * 2.2f)
    }

    // ---------------------------------------------------------------- frame loop

    /** Advance one frame; the host then draws. Call [restartClock] after a pause in frames. */
    fun frame(frameTimeNanos: Long) {
        val dt = if (last == 0L) 1f / 60f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 1f / 20f)
        last = frameTimeNanos
        t += dt
        step(dt)
    }

    fun restartClock() { last = 0L }

    /** Whether anything is moving: lets hosts slow down when the record is at rest. */
    val lively: Boolean get() = track?.isPlaying == true || demo || touching || mix < 1f || beatGlow > 0.01f

    private fun step(dt: Float) {
        val playing = track?.isPlaying == true || demo
        val targetSpin = if (playing && !reduceMotion()) (2f * PI.toFloat() / if (demo) 30f else 18f) else 0f
        spinSpeed += (targetSpin - spinSpeed) * min(1f, dt * 1.5f)
        spin += spinSpeed * dt
        if (mix < 1f) mix = min(1f, mix + dt / 0.9f)
        listen(dt)

        val k = 210f
        val damp = 2f * sqrt(k) * 0.62f
        val reach = 120f * d
        for (i in 0 until count) {
            if (!alive[i]) continue
            val a = homeA[i] + spin
            val r = homeR[i] * cell
            var hx = cx + r * cos(a)
            var hy = cy + r * sin(a)
            // Ripples push dots outward as they pass.
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
            var ax = (hx - x[i]) * k - vx[i] * damp
            var ay = (hy - y[i]) * k - vy[i] * damp
            if (touching && !seeking) {
                val dx = x[i] - touchX
                val dy = y[i] - touchY
                val dist = hypot(dx, dy)
                if (dist < reach && dist > 0.1f) {
                    val f = (1f - dist / reach)
                    ax += dx / dist * f * f * 16000f * d
                    ay += dy / dist * f * f * 16000f * d
                }
            }
            vx[i] += ax * dt
            vy[i] += ay * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
        val titleW = titleCols * pitch()
        if (titleW > width - 48 * d && t - titleBorn > 2.5f) marquee += dt * 34f * d
    }

    // ---------------------------------------------------------------- draw

    fun draw(canvas: Canvas) {
        val playing = track?.isPlaying == true || demo
        if (opaque) {
            canvas.drawColor(Color.BLACK)
            drawBackdrop(canvas)
        }
        drawGlow(canvas, playing)
        drawRing(canvas, playing)
        drawRecord(canvas, playing)
        drawTitle(canvas)
        drawInfo(canvas)
        if (showControls) drawControls(canvas)
    }

    private fun drawBackdrop(canvas: Canvas) {
        // A faint dot grid that drifts with the record.
        val step = 22f * d
        val ox = (spin * 18f * d) % step
        dot.color = Color.argb(16, 255, 255, 255)
        var yy = step / 2
        while (yy < height) {
            var xx = step / 2 - ox
            while (xx < width + step) { canvas.drawCircle(xx, yy, 1.2f * d, dot); xx += step }
            yy += step
        }
    }

    private fun drawGlow(canvas: Canvas, playing: Boolean) {
        if (glowColor != accent || glow.shader == null) {
            glowColor = accent
            glow.shader = RadialGradient(0f, 0f, 1f, intArrayOf(withAlpha(accent, 120), withAlpha(accent, 0)), null, Shader.TileMode.CLAMP)
        }
        val rad = discRadius * (1.35f + 0.12f * beatGlow)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(rad, rad)
        glow.alpha = ((if (playing) 0.55f else 0.25f) * 255 * (0.7f + 0.3f * beatGlow)).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, 1f, glow)
        canvas.restore()
    }

    private fun drawRing(canvas: Canvas, playing: Boolean) {
        val base = discRadius + 18f * d
        for (b in 0 until bands) {
            val a = b * 2f * PI.toFloat() / bands - PI.toFloat() / 2 + spin * 0.25f
            val level = ring[b]
            // Each band is a short stack of dots reaching outward.
            val stack = 1 + (level * 4f).roundToInt()
            for (s in 0 until stack) {
                val rr = base + s * 7f * d
                dot.color = if (s == stack - 1 && level > 0.62f) accent else Color.WHITE
                dot.alpha = ((if (playing) 0.85f else 0.3f) * 255 * (1f - s * 0.16f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx + rr * cos(a), cy + rr * sin(a), (2.2f - s * 0.25f) * d, dot)
            }
        }
    }

    private fun drawRecord(canvas: Canvas, playing: Boolean) {
        val m = ease(mix)
        val dim = if (playing) 1f else 0.55f
        for (i in 0 until count) {
            if (!alive[i]) continue
            val lum = lumFrom[i] + (lumTo[i] - lumFrom[i]) * m
            var r = DotArt.halftoneRadius(lum, cell)
            // Breathing wave rolling out from the centre, plus the ripples' swell.
            r *= 1f + (if (playing) 0.07f else 0.02f) * sin(t * 1.8f - homeR[i] * 0.42f)
            for (j in rippleT.indices) {
                val age = t - rippleT[j]
                if (rippleS[j] <= 0f || age > 1.6f) continue
                val dist = hypot(x[i] - rippleX[j], y[i] - rippleY[j])
                val front = age * 520f * d - dist
                r *= 1f + 0.55f * rippleS[j] * exp(-(front * front) / (2f * (30f * d) * (30f * d))) * (1f - age / 1.6f)
            }
            // A dot that's been knocked away glows a touch brighter while it flies home.
            val speed = abs(vx[i]) + abs(vy[i])
            val c = blend(colFrom[i], colTo[i], m)
            dot.color = if (speed > 900f * d) blend(c, Color.WHITE, 0.35f) else c
            dot.alpha = (255 * dim).toInt()
            canvas.drawCircle(x[i], y[i], r, dot)
        }
        // Spindle.
        dot.color = DotArt.SPINDLE_RED
        canvas.drawCircle(cx, cy, cell * (0.62f + 0.25f * beatGlow), dot)
    }

    private fun drawTitle(canvas: Canvas) {
        val p = pitch()
        val w = titleCols * p
        val scrolls = w > width - 48 * d
        val gap = 60f * d
        val start = if (scrolls) 24f * d - (marquee % (w + gap)) else (width - w) / 2f
        val age = t - titleBorn
        val copies = if (scrolls) 2 else 1
        for (copy in 0 until copies) {
            val left = start + copy * (w + gap)
            for (cX in 0 until titleCols) {
                val xx = left + (cX + .5f) * p
                if (xx < -p || xx > width + p) continue
                // Each column rains in from above, left to right.
                val local = ((age - cX * 0.012f) / 0.45f).coerceIn(0f, 1f)
                val drop = (1f - ease(local)) * -40f * d
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
        val tr = track
        canvas.drawText((tr?.artist ?: context.getString(R.string.player_idle_hint)).uppercase(), width / 2f, artistY, label)
        // Progress: a row of dots, the head pulsing with the beat.
        val count = 34
        val fraction = if (seeking) seekFraction else tr?.progress() ?: 0f
        val head = (fraction * (count - 1)).roundToInt()
        val stepX = (barRight - barLeft) / (count - 1)
        for (i in 0 until count) {
            val xx = barLeft + i * stepX
            when {
                i < head -> { dot.color = accent; canvas.drawCircle(xx, barY, 2.6f * d, dot) }
                i == head -> { dot.color = Color.WHITE; canvas.drawCircle(xx, barY, (4.6f + 2.4f * beatGlow) * d, dot) }
                else -> { dot.color = Color.argb(50, 255, 255, 255); canvas.drawCircle(xx, barY, 2.2f * d, dot) }
            }
        }
        if (tr != null && tr.durationMs > 0) {
            small.textAlign = Paint.Align.LEFT
            canvas.drawText(time((fraction * tr.durationMs).toLong()), barLeft - 4 * d, barY + 22 * d, small)
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText("-" + time(tr.durationMs - (fraction * tr.durationMs).toLong()), barRight + 4 * d, barY + 22 * d, small)
        }
    }

    private val glyphPlay = arrayOf("X......", "XXX....", "XXXXX..", "XXXXXXX", "XXXXX..", "XXX....", "X......")
    private val glyphPause = Array(7) { "XX...XX" }
    private val glyphNext = arrayOf("X.....X", "XX....X", "XXX...X", "XXXX..X", "XXX...X", "XX....X", "X.....X")
    private val glyphPrev = glyphNext.map { it.reversed() }.toTypedArray()
    private var pressed = -1

    private fun drawControls(canvas: Canvas) {
        val playing = track?.isPlaying == true
        val gap = width / 4f
        glyph(canvas, glyphPrev, gap, controlsY, 4.2f * d, pressed == 0)
        glyph(canvas, if (playing) glyphPause else glyphPlay, gap * 2, controlsY, 6.4f * d, pressed == 1)
        glyph(canvas, glyphNext, gap * 3, controlsY, 4.2f * d, pressed == 2)
    }

    private fun glyph(canvas: Canvas, rows: Array<String>, gx: Float, gy: Float, pitch: Float, down: Boolean) {
        val p = pitch * if (down) 0.86f else 1f
        dot.color = Color.WHITE
        for (r in rows.indices) for (c in rows[r].indices) {
            if (rows[r][c] != 'X') continue
            canvas.drawCircle(gx + (c - 3) * p, gy + (r - 3) * p, p * 0.38f, dot)
        }
    }

    // ---------------------------------------------------------------- touch

    fun touch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = SystemClock.uptimeMillis()
                touchX = e.x; touchY = e.y
                seeking = gestures && abs(e.y - barY) < 26 * d && track?.durationMs?.let { it > 0 } == true
                touching = !seeking && hypot(e.x - cx, e.y - cy) < discRadius + 60 * d
                pressed = if (showControls) controlAt(e.x, e.y) else -1
                if (seeking) seekFraction = ((e.x - barLeft) / (barRight - barLeft)).coerceIn(0f, 1f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = e.x; touchY = e.y
                if (seeking) seekFraction = ((e.x - barLeft) / (barRight - barLeft)).coerceIn(0f, 1f)
                if (pressed >= 0 && controlAt(e.x, e.y) != pressed) pressed = -1
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                val quick = SystemClock.uptimeMillis() - downT < 350
                when {
                    seeking -> track?.let { MusicCenter.seekTo((seekFraction * it.durationMs).toLong()); performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                    pressed >= 0 -> press(pressed)
                    gestures && touching && quick && abs(dx) > 90 * d && abs(dx) > abs(dy) * 1.5f -> {
                        // Flick the record: skip, and the dots fly the way you threw them.
                        val dir = if (dx < 0) -1 else 1
                        lastDirection = dir
                        MusicCenter.command(if (dir < 0) MusicCenter.Command.NEXT else MusicCenter.Command.PREVIOUS)
                        if (!reduceMotion()) nudge(dir)
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    gestures && touching && hypot(dx, dy) < 12 * d && hypot(e.x - cx, e.y - cy) < discRadius -> {
                        MusicCenter.command(MusicCenter.Command.TOGGLE)
                        ripple(e.x, e.y, 1.1f)
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                touching = false; seeking = false; pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> { touching = false; seeking = false; pressed = -1 }
        }
        return true
    }

    private fun controlAt(px: Float, py: Float): Int {
        if (abs(py - controlsY) > 36 * d) return -1
        val gap = width / 4f
        for (k in 0..2) if (abs(px - gap * (k + 1)) < 40 * d) return k
        return -1
    }

    private fun press(k: Int) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (k) {
            0 -> { lastDirection = 1; MusicCenter.command(MusicCenter.Command.PREVIOUS) }
            1 -> { MusicCenter.command(MusicCenter.Command.TOGGLE); ripple(cx, cy, 1f) }
            2 -> { lastDirection = -1; MusicCenter.command(MusicCenter.Command.NEXT) }
        }
    }

    /** A tap on the record from outside (the wallpaper's tap command): play/pause with a ripple. */
    fun tapAt(px: Float, py: Float): Boolean {
        if (hypot(px - cx, py - cy) > discRadius) return false
        MusicCenter.command(MusicCenter.Command.TOGGLE)
        ripple(px, py, 1.1f)
        return true
    }

    /**
     * The record and its ring as a still picture, [size] pixels square on transparent: the same
     * look as the player, for the 4×4 widget. Playing shows a lively ring; paused, a quiet one.
     */
    fun snapshot(size: Int): Bitmap {
        width = size; height = size
        discRadius = size * 0.29f
        cell = discRadius * 2f / n
        cx = size / 2f; cy = size / 2f
        val playing = track?.isPlaying == true
        t = 1.3f; mix = 1f; beatGlow = 0f
        for (b in 0 until bands) {
            val a = b * 2f * PI.toFloat() / bands
            ring[b] = if (!playing) 0.04f else (0.4f + 0.3f * sin(a * 3f) + 0.25f * sin(a * 5f + 1f)).coerceIn(0.05f, 1f)
        }
        for (i in 0 until count) if (alive[i]) { val (hx, hy) = placeHome(i)!!; x[i] = hx; y[i] = hy; vx[i] = 0f; vy[i] = 0f }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawGlow(canvas, playing)
        drawRing(canvas, playing)
        drawRecord(canvas, playing)
        return out
    }

    /** The direction of the last skip, so the next cover can fly in from that side. */
    fun takeDirection(): Int = lastDirection.also { lastDirection = 0 }

    // ---------------------------------------------------------------- helpers

    private fun reduceMotion() = android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun rand(i: Int, salt: Int): Float {
        var h = i * 374761393 + salt * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    private fun ease(v: Float) = 1f - (1f - v) * (1f - v) * (1f - v)

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun blend(a: Int, b: Int, f: Float): Int {
        if (f <= 0f) return a
        if (f >= 1f) return b
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
    }
}

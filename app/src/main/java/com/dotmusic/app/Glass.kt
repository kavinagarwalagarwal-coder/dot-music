package com.dotmusic.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Dot Phone's look: near-black glass, hairline highlight edges, soft coloured auras and the Doto
 * dot type for labels. Everything here is one paint or one gradient drawable, so it stays cheap.
 */
object Glass {
    val BG = Color.rgb(7, 8, 10)
    val INK = Color.WHITE
    val MUTED = Color.rgb(156, 164, 174)
    val DIM = Color.rgb(106, 114, 124)
    val GREEN = Color.rgb(124, 242, 178)
    val RED = Color.rgb(255, 98, 92)
    val AMBER = Color.rgb(255, 205, 120)
    val BLUE = Color.rgb(124, 178, 255)

    private var dotoFont: Typeface? = null

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun px(c: Context, v: Float) = dp(c, v).toInt()

    fun doto(c: Context): Typeface = dotoFont ?: runCatching {
        Typeface.Builder(c.assets, "fonts/Doto.ttf")
            .setFontVariationSettings("'wght' 900, 'ROND' 100").build()
    }.getOrDefault(Typeface.MONOSPACE).also { dotoFont = it }

    fun sans(c: Context) = Typeface.create("sans-serif", Typeface.NORMAL)
    fun medium(c: Context) = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun light(c: Context) = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Frosted panel: a faint diagonal gradient, a one-pixel lit edge and a soft press ripple.
     * On a near-black background this reads as glass without the cost of a real blur.
     */
    fun panel(c: Context, radius: Float = 26f, tint: Int = Color.WHITE, strength: Float = 1f): RippleDrawable {
        val top = withAlpha(tint, (30 * strength).toInt().coerceIn(0, 255))
        val bottom = withAlpha(tint, (9 * strength).toInt().coerceIn(0, 255))
        val shape = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(c, radius)
            setStroke(px(c, 1f).coerceAtLeast(1), withAlpha(tint, (44 * strength).toInt().coerceIn(0, 255)))
        }
        return RippleDrawable(ColorStateList.valueOf(withAlpha(tint, 50)), shape, null)
    }

    /**
     * A keypad key: a touch more lit than a panel, with a bright top edge so each key reads as its
     * own piece of glass and the ripple carries a little more weight.
     */
    fun key(c: Context, radius: Float = 24f): RippleDrawable {
        val shape = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(40, 255, 255, 255), Color.argb(11, 255, 255, 255))
        ).apply {
            cornerRadius = dp(c, radius)
            setStroke(px(c, 1f).coerceAtLeast(1), Color.argb(58, 255, 255, 255))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(64, 255, 255, 255)), shape, null)
    }

    /** A solid pill, for the one filled button on a screen. */
    fun fill(c: Context, color: Int, radius: Float = 26f): RippleDrawable {
        val shape = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            withAlpha(Color.WHITE, 34).let { blend(color, it, 0.10f) },
            color
        )).apply {
            cornerRadius = dp(c, radius)
            setStroke(px(c, 1f).coerceAtLeast(1), withAlpha(Color.WHITE, 40))
        }
        return RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 60)), shape, null)
    }

    /** A small rounded chip: a value, a glyph, a state. */
    fun chip(c: Context, tint: Int = Color.WHITE, radius: Float = 12f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(tint, 34), withAlpha(tint, 10))
        ).apply {
            cornerRadius = dp(c, radius)
            setStroke(px(c, 1f).coerceAtLeast(1), withAlpha(tint, 46))
        }

    /** A one-pixel divider that fades out towards both edges. */
    fun hairline(c: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(
            Color.argb(0, 255, 255, 255),
            Color.argb(30, 255, 255, 255),
            Color.argb(0, 255, 255, 255)
        )
    ).apply { cornerRadius = dp(c, 1f) }

    fun text(
        c: Context,
        content: CharSequence,
        size: Float,
        color: Int = INK,
        dot: Boolean = false,
        spacing: Float = 0f,
        medium: Boolean = false
    ) = TextView(c).apply {
        text = content
        textSize = size
        setTextColor(color)
        typeface = when {
            dot -> doto(c)
            medium -> Glass.medium(c)
            else -> sans(c)
        }
        letterSpacing = spacing
    }

    fun label(c: Context, content: CharSequence, color: Int = MUTED) =
        text(c, content, 10f, color, dot = true, spacing = 0.16f)

    /** Press feedback, the light tap the whole app uses. */
    fun tap(v: View) {
        if (Store.hapticsEnabled(v.context)) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )
}

/**
 * The backdrop every screen sits on: near-black, a mint aura above, a cool one below and a faint
 * dot grid.
 *
 * The grid and the resting auras are painted once into a static child view, so the hardware
 * renderer keeps them as a display list and they cost nothing per frame. Only [AuraGlow] animates,
 * and it draws two soft gradient circles at 30 fps: the surface feels alive without a blur, a
 * texture or a single allocation per frame.
 */
class AuraBackdrop(context: Context) : FrameLayout(context) {
    init {
        isClickable = false
        isFocusable = false
        addView(AuraField(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(AuraGlow(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

/** The still half: base colour, two resting auras and the dot grid. Drawn once. */
private class AuraField(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mint: Shader? = null
    private var cool: Shader? = null

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val reach = max(w, h).toFloat()
        mint = RadialGradient(
            w * 0.16f, h * 0.04f, reach * 0.78f,
            intArrayOf(Color.argb(44, 84, 236, 170), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        cool = RadialGradient(
            w * 0.92f, h * 0.98f, reach * 0.70f,
            intArrayOf(Color.argb(36, 92, 158, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Glass.BG)
        mint?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        cool?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.shader = null
        paint.color = Color.argb(18, 255, 255, 255)
        val step = Glass.dp(context, 28f)
        val dot = Glass.dp(context, 1.15f)
        var y = step / 2f
        while (y < height) {
            var x = step / 2f
            while (x < width) {
                canvas.drawCircle(x, y, dot, paint)
                x += step
            }
            y += step
        }
    }
}

/**
 * The moving half: the two auras drift on long, slow orbits so the background breathes with the
 * screen. Two draw calls a frame, no shader rebuilt, no garbage.
 */
private class AuraGlow(context: Context) : View(context), Choreographer.FrameCallback {
    private val mint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cool = Paint(Paint.ANTI_ALIAS_FLAG)
    private var radius = 1f
    private var t = 0f
    private var last = 0L
    private var running = false

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = max(w, h) * 0.62f
        mint.shader = RadialGradient(
            0f, 0f, radius,
            intArrayOf(Color.argb(38, 84, 236, 170), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        cool.shader = RadialGradient(
            0f, 0f, radius * 0.85f,
            intArrayOf(Color.argb(32, 92, 158, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val mx = width * (0.18f + 0.07f * sin(t * 0.21f))
        val my = height * (0.02f + 0.05f * (0.5f + 0.5f * cos(t * 0.17f)))
        canvas.save()
        canvas.translate(mx, my)
        canvas.drawCircle(0f, 0f, radius, mint)
        canvas.restore()

        val cx = width * (0.92f - 0.06f * sin(t * 0.15f))
        val cy = height * (0.99f - 0.05f * (0.5f + 0.5f * cos(t * 0.19f)))
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, radius * 0.85f, cool)
        canvas.restore()
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
        val dt = if (last == 0L) 0f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.2f)
        last = frameTimeNanos
        t += dt
        invalidate()
        Choreographer.getInstance().postFrameCallbackDelayed(this, 33L)
    }
}

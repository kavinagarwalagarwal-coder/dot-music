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
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Dot Phone's look: a near-black canvas, one flat card tone, hairline edges, a serif display face
 * for page titles and the Doto dot type for small labels. Everything here is one paint or one
 * gradient drawable, so it stays cheap.
 */
object Glass {
    val BG = Color.rgb(5, 5, 6)

    /** The one surface tone: every card, sheet and key is a shade of this. */
    val CARD = Color.rgb(23, 23, 26)

    /** The sheet the keypad sits on, a touch darker than a card. */
    val SINK = Color.rgb(13, 13, 15)

    /** A one-pixel edge, the only thing separating a card from the canvas. */
    const val EDGE = 0x1CFFFFFF

    val INK = Color.WHITE
    val MUTED = Color.rgb(142, 142, 147)
    val DIM = Color.rgb(98, 98, 104)
    val GREEN = Color.rgb(48, 209, 88)
    val ON_GREEN = Color.rgb(3, 18, 9)
    val RED = Color.rgb(255, 69, 58)
    val AMBER = Color.rgb(255, 205, 120)
    val BLUE = Color.rgb(124, 178, 255)

    private var dotoFont: Typeface? = null
    private var serifFont: Typeface? = null

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun px(c: Context, v: Float) = dp(c, v).toInt()

    fun doto(c: Context): Typeface = dotoFont ?: runCatching {
        Typeface.Builder(c.assets, "fonts/Doto.ttf")
            .setFontVariationSettings("'wght' 900, 'ROND' 100").build()
    }.getOrDefault(Typeface.MONOSPACE).also { dotoFont = it }

    fun serif(c: Context): Typeface = serifFont ?: runCatching {
        Typeface.create("serif", Typeface.NORMAL)
    }.getOrDefault(Typeface.SERIF).also { serifFont = it }

    fun sans(c: Context) = Typeface.create("sans-serif", Typeface.NORMAL)
    fun medium(c: Context) = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun light(c: Context) = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun rounded(c: Context, color: Int, radius: Float, stroke: Float = 1f) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius)
            if (stroke > 0f) setStroke(px(c, stroke).coerceAtLeast(1), EDGE)
        }

    /**
     * A card: one flat tone and a hairline edge. No gradient, no blur — on a near-black canvas the
     * edge alone is what makes it read as a surface.
     */
    fun card(c: Context, radius: Float = 18f, tone: Int = CARD): GradientDrawable =
        rounded(c, tone, radius)

    /** A tappable card: the same surface with a soft press ripple. */
    fun panel(c: Context, radius: Float = 18f, tint: Int = Color.WHITE, strength: Float = 1f): RippleDrawable {
        val tone = if (strength > 1f) blend(CARD, tint, 0.035f * strength) else CARD
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(30, 255, 255, 255)),
            rounded(c, tone, radius),
            null
        )
    }

    /** The keypad sheet: the darkest surface, so the keys on it read as raised. */
    fun sheet(c: Context, radius: Float = 28f): GradientDrawable = rounded(c, SINK, radius)

    /** A keypad key: a lighter flat pill on the sheet. */
    fun key(c: Context, radius: Float = 22f): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.argb(48, 255, 255, 255)),
        rounded(c, blend(CARD, Color.WHITE, 0.045f), radius),
        null
    )

    /** A solid pill, for the one filled button on a screen. Flat, no gloss. */
    fun fill(c: Context, color: Int, radius: Float = 26f): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.argb(56, 255, 255, 255)),
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius)
        },
        null
    )

    /** A small rounded chip: a value, a glyph, a state. */
    fun chip(c: Context, tint: Int = Color.WHITE, radius: Float = 12f): GradientDrawable =
        GradientDrawable().apply {
            setColor(withAlpha(tint, 26))
            cornerRadius = dp(c, radius)
            setStroke(px(c, 1f).coerceAtLeast(1), withAlpha(tint, 54))
        }

    /** A one-pixel divider, full width, as inside a grouped list. */
    fun hairline(c: Context): GradientDrawable = GradientDrawable().apply {
        setColor(EDGE)
    }

    /** A row-shaped divider, optionally inset to line up under the row's text. */
    fun divider(c: Context, indent: Int = 0): View = View(c).apply {
        background = hairline(c)
        layoutParams = LinearLayout.LayoutParams(-1, px(c, 1f)).apply { leftMargin = indent }
    }

    /** One card holding several rows: the grouped list every screen uses. */
    fun group(c: Context, radius: Float = 18f): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = card(c, radius)
        clipToOutline = true
    }

    /** A press ripple for a row that sits inside a [group] and carries no surface of its own. */
    fun row(c: Context): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(28, 255, 255, 255)), null, null)

    /** The little grabber bar at the top of a sheet. */
    fun grabber(c: Context): View = View(c).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(52, 255, 255, 255))
            cornerRadius = dp(c, 99f)
        }
    }

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

    /** A page title: the serif face, the one big voice on a screen. */
    fun display(c: Context, content: CharSequence, size: Float = 32f, color: Int = INK): TextView =
        TextView(c).apply {
            text = content
            textSize = size
            setTextColor(color)
            typeface = serif(c)
            letterSpacing = -0.01f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    /** The small grey label that sits above a title or a group of rows. */
    fun section(c: Context, content: CharSequence, color: Int = MUTED): TextView =
        TextView(c).apply {
            text = content
            textSize = 12.5f
            setTextColor(color)
            typeface = sans(c)
            letterSpacing = 0.01f
            includeFontPadding = false
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
 * The backdrop every screen sits on: near-black, a whisper of mint above and a cool one below, and
 * a very faint dot grid.
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
            w * 0.16f, h * 0.02f, reach * 0.62f,
            intArrayOf(Color.argb(20, 84, 236, 170), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        cool = RadialGradient(
            w * 0.92f, h * 1.02f, reach * 0.58f,
            intArrayOf(Color.argb(15, 92, 158, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
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
        paint.color = Color.argb(8, 255, 255, 255)
        val step = Glass.dp(context, 28f)
        val dot = Glass.dp(context, 1.1f)
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
        radius = max(w, h) * 0.55f
        mint.shader = RadialGradient(
            0f, 0f, radius,
            intArrayOf(Color.argb(16, 84, 236, 170), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        cool.shader = RadialGradient(
            0f, 0f, radius * 0.85f,
            intArrayOf(Color.argb(13, 92, 158, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
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

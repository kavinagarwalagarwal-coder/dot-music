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
import android.view.View
import android.widget.TextView
import kotlin.math.max

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

    fun circle(c: Context, color: Int = Color.TRANSPARENT, radius: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            cornerRadius = dp(c, radius)
            setColor(color)
            setStroke(px(c, 1f).coerceAtLeast(1), withAlpha(Color.WHITE, 46))
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

    fun label(c: Context, content: CharSequence, color: Int = MUTED) =
        text(c, content, 10f, color, dot = true, spacing = 0.16f)

    /** Press feedback, the light tap the whole app uses. */
    fun tap(v: View) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )
}

/**
 * The backdrop every screen sits on: near-black, two soft auras (mint above, cool blue below) and a
 * faint drifting dot grid. Static, so it costs one draw and no frames.
 */
class AuraBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mint: Shader? = null
    private var cool: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val reach = max(w, h).toFloat()
        mint = RadialGradient(
            w * 0.16f, h * 0.06f, reach * 0.72f,
            intArrayOf(Color.argb(56, 84, 236, 170), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        cool = RadialGradient(
            w * 0.92f, h * 0.95f, reach * 0.66f,
            intArrayOf(Color.argb(46, 92, 158, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
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
        paint.color = Color.argb(20, 255, 255, 255)
        val step = Glass.dp(context, 27f)
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

package com.dotmusic.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Shared look for Dot's native views: the Doto dot font, dark pills, dp. */
object DotUi {
    @Volatile private var font: Typeface? = null

    fun doto(c: Context): Typeface = font ?: runCatching {
        Typeface.Builder(c.assets, "fonts/Doto.ttf").setFontVariationSettings("'wght' 900, 'ROND' 100").build()
    }.getOrDefault(Typeface.MONOSPACE).also { font = it }

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    fun pill(c: Context, fill: Int = Color.rgb(24, 24, 24), radius: Float = 22f) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
        GradientDrawable().apply {
            cornerRadius = dp(c, radius); setColor(fill); setStroke(dp(c, 1f).toInt(), Color.argb(36, 255, 255, 255))
        }, null)

    /** A stable colour per artist, for the little dot beside each pick. */
    fun artistColor(name: String): Int {
        val hue = ((SongKeys.norm(name).hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.65f, 1f))
    }
}

/**
 * "For you": search YouTube Music, and Dot's recommendation shelves. Lies over the player;
 * tap anything and it plays on YouTube Music.
 */
class ForYouPanel(context: Context) : FrameLayout(context), Taste.Observer {
    var onClose: () -> Unit = {}
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { isFillViewport = true; addView(column) }
    private val search: EditText
    private val shelvesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(v: Float) = DotUi.dp(context, v)
    private val doto = DotUi.doto(context)

    init {
        setBackgroundColor(Color.argb(246, 0, 0, 0))
        isClickable = true
        visibility = GONE
        addView(scroll, LayoutParams(-1, -1))
        column.setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(40f).toInt())

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.for_you).uppercase(); typeface = doto; textSize = 30f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            text = "✕"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = DotUi.pill(context); contentDescription = context.getString(R.string.close)
            setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()))
        column.addView(header)

        search = EditText(context).apply {
            hint = context.getString(R.string.search_hint)
            setHintTextColor(Color.argb(120, 255, 255, 255)); setTextColor(Color.WHITE); textSize = 16f
            isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = DotUi.pill(context, Color.rgb(20, 20, 20), 26f)
            setPadding(dp(20f).toInt(), 0, dp(20f).toInt(), 0)
            setOnEditorActionListener { v, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) { playSearch(v.text.toString()); true } else false
            }
        }
        column.addView(search, LinearLayout.LayoutParams(-1, dp(52f).toInt()).apply { topMargin = dp(14f).toInt() })
        column.addView(note(context.getString(R.string.search_note)))
        column.addView(shelvesBox)
    }

    val showing get() = visibility == VISIBLE

    fun show() {
        visibility = VISIBLE
        alpha = 0f; translationY = dp(40f)
        animate().alpha(1f).translationY(0f).setDuration(220).start()
        scroll.scrollTo(0, 0)
        Taste.observe(this)
        Taste.refresh()
    }

    fun hide() {
        keyboard(false)
        Taste.forget(this)
        animate().alpha(0f).translationY(dp(40f)).setDuration(160).withEndAction { visibility = GONE; onClose() }.start()
    }

    override fun onShelves(shelves: TasteEngine.Shelves) = render(shelves)

    private fun render(s: TasteEngine.Shelves) {
        shelvesBox.removeAllViews()
        if (!YouTubeMusic.installed(context)) {
            shelvesBox.addView(action(context.getString(R.string.get_yt_music), context.getString(R.string.get_yt_music_note)) {
                YouTubeMusic.getIt(context)
            })
        }
        if (s.listens < 3) {
            shelvesBox.addView(heading(context.getString(R.string.shelf_learning)))
            shelvesBox.addView(note(context.getString(R.string.learning_note)))
        }
        shelf(context.getString(R.string.shelf_up_next), s.upNext)
        val queue = MusicCenter.queue()
        if (queue.isNotEmpty()) {
            shelvesBox.addView(heading(context.getString(R.string.shelf_queue)))
            for (q in queue.take(6)) shelvesBox.addView(row(q.title, q.subtitle, q.subtitle) { MusicCenter.jumpTo(q.id); hide() })
        }
        shelf(context.getString(R.string.shelf_for_now, TasteEngine.SLOT_NAMES[s.slot]), s.forNow)
        shelf(context.getString(R.string.shelf_on_repeat), s.onRepeat)
        shelf(context.getString(R.string.shelf_rediscover), s.rediscover)
        shelf(context.getString(R.string.shelf_explore), s.explore)
        if (s.listens > 0) shelvesBox.addView(note(context.getString(R.string.learned_from, s.listens)).apply {
            setPadding(0, dp(28f).toInt(), 0, 0)
        })
    }

    private fun shelf(title: String, picks: List<TasteEngine.Pick>) {
        if (picks.isEmpty()) return
        shelvesBox.addView(heading(title))
        for (p in picks) {
            val artistPick = p.kind == TasteEngine.Kind.ARTIST
            val name = if (artistPick) p.artist else p.title
            val sub = if (artistPick) context.getString(R.string.artist_mix_note, p.reason) else "${p.artist} · ${p.reason}"
            shelvesBox.addView(row(name, sub, p.artist) { play(p) })
        }
    }

    private fun play(p: TasteEngine.Pick) {
        if (!YouTubeMusic.play(context, p)) Toast.makeText(context, R.string.yt_unreachable, Toast.LENGTH_LONG).show()
        hide()
    }

    private fun playSearch(q: String) {
        if (q.isBlank()) return
        keyboard(false)
        if (!YouTubeMusic.play(context, q)) Toast.makeText(context, R.string.yt_unreachable, Toast.LENGTH_LONG).show()
        search.setText("")
        hide()
    }

    private fun keyboard(show: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        if (show) imm.showSoftInput(search, 0) else imm.hideSoftInputFromWindow(search.windowToken, 0)
        if (!show) search.clearFocus()
    }

    // ---------------------------------------------------------------- views

    private fun heading(s: String) = TextView(context).apply {
        text = s.uppercase(); typeface = doto; textSize = 17f; setTextColor(Color.WHITE)
        setPadding(0, dp(26f).toInt(), 0, dp(6f).toInt())
    }

    private fun note(s: String) = TextView(context).apply {
        text = s; textSize = 13f; setTextColor(Color.argb(150, 255, 255, 255)); setLineSpacing(0f, 1.15f)
        setPadding(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), 0)
    }

    private fun row(title: String, subtitle: String, artist: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), null, ColorDrawable(Color.WHITE))
        setPadding(dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt())
        minimumHeight = dp(56f).toInt()
        setOnClickListener { onClick() }
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DotUi.artistColor(artist)) }
        }, LinearLayout.LayoutParams(dp(10f).toInt(), dp(10f).toInt()).apply { rightMargin = dp(16f).toInt() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (subtitle.isNotBlank()) addView(TextView(context).apply {
                text = subtitle; textSize = 13f; setTextColor(Color.argb(140, 255, 255, 255)); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun action(title: String, subtitle: String, onClick: () -> Unit) = row(title, subtitle, title, onClick).apply {
        background = DotUi.pill(context, Color.rgb(28, 28, 28), 18f)
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f).toInt() }
    }
}

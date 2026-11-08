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
import android.widget.ImageView
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

    /** Tell the person when a song couldn't start quietly in the player app. */
    fun reported(c: Context, outcome: PlayerApp.Outcome) {
        val app = PlayerApp.name(c)
        when (outcome.route) {
            PlayerApp.Route.OPENED_APP -> Toast.makeText(c, c.getString(R.string.player_opened, app), Toast.LENGTH_LONG).show()
            PlayerApp.Route.NONE -> Toast.makeText(c, c.getString(R.string.player_unreachable, app), Toast.LENGTH_LONG).show()
            else -> {}
        }
    }

    /**
     * Play a recommendation: in Dot's own player when an online source is on (by its remembered id,
     * else by searching), otherwise through the chosen player app.
     */
    fun playPick(c: Context, pick: TasteEngine.Pick) {
        if (!Catalogs.anyOn(c)) return PlayerApp.play(c, pick) { reported(c, it) }
        Catalogs.resolve(c, pick) { t ->
            if (t != null) DotPlayback.play(c, t)
            else Toast.makeText(c, c.getString(R.string.not_found, pick.title.ifBlank { pick.artist }), Toast.LENGTH_LONG).show()
        }
    }

    /** "YouTube, Audius and Jamendo": the sources that are on. */
    fun sourceNames(c: Context): String {
        val names = buildList {
            if (Catalogs.youtubeOn(c)) add("YouTube")
            if (Catalogs.audiusOn(c)) add("Audius")
            if (Catalogs.jamendoOn(c)) add("Jamendo")
        }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + c.getString(R.string.and_join) + names.last()
        }
    }

    /** A stable colour per artist, for the little dot beside each pick. */
    fun artistColor(name: String): Int {
        val hue = ((SongKeys.norm(name).hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.65f, 1f))
    }
}

/**
 * "For you": search, Dot's queue and recommendation shelves, over the player. With an online
 * source on, everything plays in Dot's own player; otherwise it goes to the chosen player app.
 */
class ForYouPanel(context: Context) : FrameLayout(context), Taste.Observer {
    var onClose: () -> Unit = {}
    /** Room left at the bottom for the YouTube card, which stays on top. */
    var bottomSpace = 0
        set(v) { field = v; column.setPadding(column.paddingLeft, column.paddingTop, column.paddingRight, dp(40f).toInt() + v) }

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { isFillViewport = true; addView(column) }
    private val search: EditText
    private val searchNote: TextView
    private val resultsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val shelvesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val discoverBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(v: Float) = DotUi.dp(context, v)
    private val doto = DotUi.doto(context)
    private val online get() = Catalogs.anyOn(context)
    private var lastShelves: TasteEngine.Shelves? = null
    private var discover: List<Pair<TasteEngine.Pick, OnlineTrack>> = emptyList()
    private var discoverAt = 0L
    private val queueWatch = DotPlayback.QueueObserver { lastShelves?.let(::render) }

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
            setHintTextColor(Color.argb(120, 255, 255, 255)); setTextColor(Color.WHITE); textSize = 16f
            isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = DotUi.pill(context, Color.rgb(20, 20, 20), 26f)
            setPadding(dp(20f).toInt(), 0, dp(20f).toInt(), 0)
            setOnEditorActionListener { v, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) { submit(v.text.toString()); true } else false
            }
        }
        column.addView(search, LinearLayout.LayoutParams(-1, dp(52f).toInt()).apply { topMargin = dp(14f).toInt() })
        searchNote = note("")
        column.addView(searchNote)
        column.addView(resultsBox)
        column.addView(shelvesBox)
    }

    val showing get() = visibility == VISIBLE

    fun show() {
        if (online) {
            search.hint = context.getString(R.string.search_hint_online)
            searchNote.text = context.getString(R.string.search_note_online, DotUi.sourceNames(context))
        } else {
            search.hint = context.getString(R.string.search_hint, PlayerApp.name(context))
            searchNote.text = context.getString(R.string.search_note, PlayerApp.name(context))
        }
        visibility = VISIBLE
        alpha = 0f; translationY = dp(40f)
        animate().alpha(1f).translationY(0f).setDuration(220).start()
        scroll.scrollTo(0, 0)
        Taste.observe(this)
        DotPlayback.observeQueue(queueWatch)
        Taste.refresh()
        loadDiscover()
    }

    fun hide() {
        keyboard(false)
        Taste.forget(this)
        DotPlayback.forgetQueue(queueWatch)
        animate().alpha(0f).translationY(dp(40f)).setDuration(160).withEndAction { visibility = GONE; onClose() }.start()
    }

    override fun onShelves(shelves: TasteEngine.Shelves) = render(shelves)

    // ---------------------------------------------------------------- search

    private fun submit(q: String) {
        if (q.isBlank()) return
        keyboard(false)
        if (!online) {
            PlayerApp.play(context, q) { DotUi.reported(context, it) }
            search.setText("")
            return hide()
        }
        resultsBox.removeAllViews()
        resultsBox.addView(heading(context.getString(R.string.searching)))
        Catalogs.search(context, q) { r -> showResults(q, r) }
    }

    private fun showResults(q: String, r: Catalogs.Results) {
        resultsBox.removeAllViews()
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(heading(context.getString(R.string.results_for, q)), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(context).apply {
            text = context.getString(R.string.clear); textSize = 14f; setTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt())
            setOnClickListener { resultsBox.removeAllViews(); search.setText("") }
        })
        resultsBox.addView(head)
        if (r.tracks.isEmpty()) resultsBox.addView(note(context.getString(R.string.no_results)))
        for (t in r.tracks) {
            val sub = listOf(t.artist, t.source.label, duration(t.durationMs)).filter { it.isNotBlank() }.joinToString(" · ")
            resultsBox.addView(row(t.title, sub, t.artist, t.artUrl, onPlus = {
                DotPlayback.playNext(context, t)
                Toast.makeText(context, context.getString(R.string.plays_next, t.title), Toast.LENGTH_SHORT).show()
            }) {
                DotPlayback.play(context, t)
                hide()
            })
        }
        if (r.errors.isNotEmpty()) resultsBox.addView(note(context.getString(R.string.search_errors, r.errors.joinToString("\n"))))
    }

    // ---------------------------------------------------------------- shelves

    private fun render(s: TasteEngine.Shelves) {
        lastShelves = s
        shelvesBox.removeAllViews()
        if (online) {
            if (!Catalogs.youtubeOn(context)) shelvesBox.addView(action(context.getString(R.string.add_youtube),
                context.getString(R.string.add_youtube_note)) { context.startActivity(android.content.Intent(context, MainActivity::class.java)) })
        } else if (!PlayerApp.installed(context)) {
            val app = PlayerApp.name(context)
            shelvesBox.addView(action(context.getString(R.string.get_player, app), context.getString(R.string.get_player_note, app)) {
                PlayerApp.getIt(context)
            })
        }
        // What's queued: Dot's own, or the player app's.
        val upcoming = if (MusicCenter.current?.isDot == true) DotPlayback.upcoming() else emptyList()
        if (upcoming.isNotEmpty()) {
            shelvesBox.addView(heading(context.getString(R.string.shelf_queue_dot)))
            upcoming.take(8).forEachIndexed { i, t ->
                shelvesBox.addView(row(t.title, "${t.artist} · ${t.source.label}", t.artist, t.artUrl) { DotPlayback.jumpUpcoming(i); hide() })
            }
        } else if (!online) {
            val queue = MusicCenter.queue()
            if (queue.isNotEmpty()) {
                shelvesBox.addView(heading(context.getString(R.string.shelf_queue)))
                for (q in queue.take(6)) shelvesBox.addView(row(q.title, q.subtitle, q.subtitle) { MusicCenter.jumpTo(q.id); hide() })
            }
        }
        if (s.listens < 3) {
            shelvesBox.addView(heading(context.getString(R.string.shelf_learning)))
            shelvesBox.addView(note(context.getString(R.string.learning_note)))
        }
        shelf(context.getString(R.string.shelf_up_next), s.upNext)
        shelf(context.getString(R.string.shelf_for_now, TasteEngine.SLOT_NAMES[s.slot]), s.forNow)
        shelf(context.getString(R.string.shelf_on_repeat), s.onRepeat)
        shelf(context.getString(R.string.shelf_rediscover), s.rediscover)
        if (online) shelvesBox.addView(discoverBox)
        shelf(context.getString(R.string.shelf_explore), s.explore)
        if (s.listens > 0) shelvesBox.addView(note(context.getString(R.string.learned_from, s.listens)).apply {
            setPadding(0, dp(28f).toInt(), 0, 0)
        })
    }

    /** New music from the free charts, ranked by your taste. Fetched at most every 10 minutes. */
    private fun loadDiscover() {
        if (!online) return
        val now = System.currentTimeMillis()
        if (discover.isNotEmpty() && now - discoverAt < 10 * 60_000L) return renderDiscover()
        fun rank(pool: List<OnlineTrack>) = Taste.withEngine<List<TasteEngine.Pick>>({ ranked ->
            val byId = pool.associateBy { it.mediaId }
            discover = ranked.mapNotNull { p -> byId[p.mediaId]?.let { p to it } }
            discoverAt = System.currentTimeMillis()
            renderDiscover()
        }) { engine -> engine.rankNew(pool.map(DotPlayback::candidate), 8) }
        // Your favourite genre's chart first; the overall chart if that's thin.
        Taste.withEngine<String?>({ genre ->
            Catalogs.trending(context, genre) { list ->
                if (list.size >= 6 || genre == null) rank(list) else Catalogs.trending(context, null) { all -> rank(list + all) }
            }
        }) { engine -> engine.topGenre() }
    }

    private fun renderDiscover() {
        discoverBox.removeAllViews()
        if (discover.isEmpty()) return
        discoverBox.addView(heading(context.getString(R.string.shelf_discover)))
        for ((p, t) in discover) {
            discoverBox.addView(row(t.title, "${t.artist} · ${p.reason}", t.artist, t.artUrl, onPlus = {
                DotPlayback.playNext(context, t)
                Toast.makeText(context, context.getString(R.string.plays_next, t.title), Toast.LENGTH_SHORT).show()
            }) { DotPlayback.play(context, t); hide() })
        }
    }

    private fun shelf(title: String, picks: List<TasteEngine.Pick>) {
        if (picks.isEmpty()) return
        shelvesBox.addView(heading(title))
        for (p in picks) {
            val artistPick = p.kind == TasteEngine.Kind.ARTIST
            val name = if (artistPick) p.artist else p.title
            val sub = if (artistPick) context.getString(R.string.artist_mix_note, p.reason) else "${p.artist} · ${p.reason}"
            shelvesBox.addView(row(name, sub, p.artist, p.artUrl) { DotUi.playPick(context, p); hide() })
        }
    }

    private fun keyboard(show: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        if (show) imm.showSoftInput(search, 0) else imm.hideSoftInputFromWindow(search.windowToken, 0)
        if (!show) search.clearFocus()
    }

    private fun duration(ms: Long): String = if (ms <= 0) "" else "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    // ---------------------------------------------------------------- views

    private fun heading(s: String) = TextView(context).apply {
        text = s.uppercase(); typeface = doto; textSize = 17f; setTextColor(Color.WHITE)
        setPadding(0, dp(26f).toInt(), 0, dp(6f).toInt())
    }

    private fun note(s: String) = TextView(context).apply {
        text = s; textSize = 13f; setTextColor(Color.argb(150, 255, 255, 255)); setLineSpacing(0f, 1.15f)
        setPadding(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), 0)
    }

    private fun row(
        title: String, subtitle: String, artist: String, artUrl: String = "",
        onPlus: (() -> Unit)? = null, onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), null, ColorDrawable(Color.WHITE))
        setPadding(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt())
        minimumHeight = dp(60f).toInt()
        setOnClickListener { onClick() }
        if (artUrl.isNotBlank()) {
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(8f); setColor(DotUi.artistColor(artist) and 0x40FFFFFF) }
                clipToOutline = true
            }
            addView(img, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).apply { rightMargin = dp(14f).toInt() })
            ArtLoader.load(artUrl, 128) { b -> if (b != null) img.setImageBitmap(b) }
        } else {
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DotUi.artistColor(artist)) }
            }, LinearLayout.LayoutParams(dp(10f).toInt(), dp(10f).toInt()).apply { leftMargin = dp(4f).toInt(); rightMargin = dp(20f).toInt() })
        }
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
        if (onPlus != null) addView(TextView(context).apply {
            text = "+"; textSize = 22f; setTextColor(Color.argb(200, 255, 255, 255)); gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.play_next)
            background = DotUi.pill(context, Color.rgb(26, 26, 26), 20f)
            setOnClickListener { onPlus() }
        }, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()).apply { leftMargin = dp(10f).toInt() })
    }

    private fun action(title: String, subtitle: String, onClick: () -> Unit) = row(title, subtitle, title, onClick = onClick).apply {
        background = DotUi.pill(context, Color.rgb(28, 28, 28), 18f)
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f).toInt() }
    }
}

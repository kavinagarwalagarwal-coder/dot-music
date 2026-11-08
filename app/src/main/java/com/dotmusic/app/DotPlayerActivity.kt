package com.dotmusic.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The app: the full-screen dot player, playing through YouTube Music. A slim top bar opens
 * For You (search and recommendations) and settings; the bar at the bottom shows Dot's next pick.
 */
class DotPlayerActivity : Activity(), MusicCenter.Observer, Taste.Observer {
    lateinit var player: DotPlayerView
        private set
    private lateinit var panel: ForYouPanel
    private lateinit var topBar: LinearLayout
    private lateinit var nextBar: LinearLayout
    private lateinit var nextLabel: TextView
    private lateinit var nextText: TextView
    private var nextAction: () -> Unit = {}
    /** YouTube's player for YouTube songs: a visible card above the bottom bar, as YouTube requires. */
    private lateinit var videoCard: FrameLayout
    private var youtube: YouTubeEmbed? = null
    private var bottomInset = 0
    private val queueWatch = DotPlayback.QueueObserver { refreshNextBar() }
    private val beat by lazy { BeatListener(this) { bands -> player.spectrum = bands } }
    private fun dp(v: Float) = DotUi.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        setContentView(buildUi())
        if (intent?.getBooleanExtra(EXTRA_FOR_YOU, false) == true) panel.post { panel.show() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FOR_YOU, false)) panel.show()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        player = DotPlayerView(this).apply {
            stage.topReserve = dp(56f)
            stage.bottomReserve = dp(80f)
        }
        root.addView(player, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f).toInt(), 0, dp(14f).toInt(), 0)
        }
        topBar.addView(TextView(this).apply {
            text = getString(R.string.app_name).uppercase(); typeface = DotUi.doto(this@DotPlayerActivity)
            textSize = 18f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(chip(getString(R.string.for_you).uppercase()) { panel.show() })
        topBar.addView(chip("•••", square = true) { startActivity(Intent(this, MainActivity::class.java)) }.apply {
            contentDescription = getString(R.string.settings_title)
        })
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(56f).toInt(), Gravity.TOP))

        nextBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = DotUi.pill(this@DotPlayerActivity, Color.rgb(18, 18, 18), 20f)
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            setOnClickListener { nextAction() }
        }
        nextLabel = TextView(this).apply {
            typeface = DotUi.doto(this@DotPlayerActivity); textSize = 12f; setTextColor(Color.argb(160, 255, 255, 255))
        }
        nextText = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        nextBar.addView(nextLabel)
        nextBar.addView(nextText)
        root.addView(nextBar, FrameLayout.LayoutParams(-1, dp(64f).toInt(), Gravity.BOTTOM).apply {
            leftMargin = dp(16f).toInt(); rightMargin = dp(16f).toInt(); bottomMargin = dp(12f).toInt()
        })

        videoCard = FrameLayout(this).apply {
            visibility = View.GONE
            background = DotUi.pill(this@DotPlayerActivity, Color.BLACK, 14f)
            clipToOutline = true
            // Never covered, not even by For you (YouTube's embed rules).
            elevation = dp(24f)
        }
        root.addView(videoCard, FrameLayout.LayoutParams(dp(VIDEO_W).toInt(), dp(VIDEO_H).toInt(), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16f).toInt(); bottomMargin = dp(12f + 64f + 10f).toInt()
        })

        panel = ForYouPanel(this).apply { onClose = { refreshNextBar() } }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))

        // Keep the bars clear of the status bar, cutouts and gesture bar.
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutout)
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = top
            (nextBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(12f).toInt() + bottom
            (videoCard.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(12f + 64f + 10f).toInt() + bottom
            bottomInset = bottom
            topBar.requestLayout(); nextBar.requestLayout(); videoCard.requestLayout()
            panel.setPadding(0, top, 0, bottom)
            insets
        }
        return root
    }

    private fun chip(label: String, square: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; typeface = DotUi.doto(this@DotPlayerActivity); textSize = 14f; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = DotUi.pill(this@DotPlayerActivity)
        setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(if (square) dp(52f).toInt() else -2, dp(40f).toInt()).apply {
            leftMargin = dp(8f).toInt()
        }
    }

    override fun onStart() {
        super.onStart()
        DotPlayback.attach(this)
        // YouTube's player lives only while Dot is on screen.
        if (Catalogs.youtubeOn(this)) {
            val e = youtube ?: YouTubeEmbed(this).also { youtube = it; videoCard.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            DotPlayback.attachYouTube(e)
        } else youtube?.let { DotPlayback.detachYouTube(it); videoCard.removeView(it); it.release(); youtube = null }
        refreshVideoCard()
    }

    override fun onStop() {
        youtube?.let { DotPlayback.detachYouTube(it) }
        super.onStop()
    }

    override fun onDestroy() {
        youtube?.let { DotPlayback.detachYouTube(it); it.release() }
        youtube = null
        super.onDestroy()
    }

    /** Show the YouTube card only while a YouTube song is current, and give the record room for it. */
    private fun refreshVideoCard() {
        val show = youtube != null && MusicCenter.current?.mediaId?.startsWith("youtube:") == true
        val target = if (show) View.VISIBLE else View.GONE
        if (videoCard.visibility == target) return
        videoCard.visibility = target
        player.stage.bottomReserve = dp(if (show) 80f + VIDEO_H + 10f else 80f)
        player.relayout()
        panel.bottomSpace = if (show) dp(VIDEO_H + 96f).toInt() else 0
    }

    override fun onResume() {
        super.onResume()
        MusicCenter.attach(this)
        MusicCenter.observe(this)
        Taste.observe(this)
        DotPlayback.observeQueue(queueWatch)
        player.start()
        beat.start()
        refreshNextBar()
    }

    override fun onPause() {
        MusicCenter.forget(this)
        Taste.forget(this)
        DotPlayback.forgetQueue(queueWatch)
        player.stop()
        beat.stop()
        super.onPause()
    }

    @Deprecated("Platform back handling is enough here.")
    override fun onBackPressed() {
        if (panel.showing) panel.hide() else super.onBackPressed()
    }

    override fun onTrack(track: Track?) {
        player.setTrack(track, player.takeDirection())
        // A now-playing screen: stay awake while the music plays.
        if (track?.isPlaying == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshVideoCard()
        refreshNextBar()
    }

    override fun onShelves(shelves: TasteEngine.Shelves) = refreshNextBar()

    /** The bottom bar: setup steps first, then Dot's next pick. */
    private fun refreshNextBar() {
        if (!::nextBar.isInitialized) return
        val pick = Taste.nextPick()
        if (Catalogs.anyOn(this)) {
            // Dot's own player: no other app or permission needed.
            val queued = DotPlayback.upcoming().firstOrNull()
            when {
                MusicCenter.current?.isDot == true && queued != null ->
                    showBar(R.string.bar_up_next, "${queued.title} — ${queued.artist}") { DotPlayback.jumpUpcoming(0) }
                pick != null -> showBar(R.string.bar_next, "${pick.title.ifBlank { pick.artist }} — ${pick.artist}") { DotUi.playPick(this, pick) }
                MusicCenter.current == null -> showBar(R.string.bar_start, getString(R.string.bar_start_text)) { panel.show() }
                else -> showBar(R.string.bar_learning, getString(R.string.bar_learning_text)) { panel.show() }
            }
            return
        }
        when {
            !MusicListener.enabled(this) -> showBar(getString(R.string.bar_setup, PlayerApp.name(this)), getString(R.string.bar_setup_text)) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            !PlayerApp.installed(this) -> showBar(getString(R.string.get_player, PlayerApp.name(this)),
                getString(R.string.get_player_note, PlayerApp.name(this))) { PlayerApp.getIt(this) }
            pick != null -> showBar(if (DotPrefs.dj(this)) R.string.bar_next_dj else R.string.bar_next,
                if (pick.kind == TasteEngine.Kind.ARTIST) getString(R.string.artist_mix, pick.artist) else "${pick.title} — ${pick.artist}") {
                PlayerApp.play(this, pick) { DotUi.reported(this, it) }
            }
            MusicCenter.current == null -> showBar(R.string.bar_start, getString(R.string.bar_start_text)) { panel.show() }
            else -> showBar(R.string.bar_learning, getString(R.string.bar_learning_text)) { panel.show() }
        }
    }

    private fun showBar(label: Int, text: String, action: () -> Unit) = showBar(getString(label), text, action)

    private fun showBar(label: String, text: String, action: () -> Unit) {
        nextLabel.text = label.uppercase()
        nextText.text = text
        nextAction = action
    }

    companion object {
        const val EXTRA_FOR_YOU = "for_you"
        /** The YouTube card: 16:9, comfortably over YouTube's 200×200 px minimum on any phone. */
        private const val VIDEO_W = 224f
        private const val VIDEO_H = 126f
    }
}

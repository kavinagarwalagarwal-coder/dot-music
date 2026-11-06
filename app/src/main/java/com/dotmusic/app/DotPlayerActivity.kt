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
            topBar.requestLayout(); nextBar.requestLayout()
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

    override fun onResume() {
        super.onResume()
        MusicCenter.attach(this)
        MusicCenter.observe(this)
        Taste.observe(this)
        player.start()
        beat.start()
        refreshNextBar()
    }

    override fun onPause() {
        MusicCenter.forget(this)
        Taste.forget(this)
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
        refreshNextBar()
    }

    override fun onShelves(shelves: TasteEngine.Shelves) = refreshNextBar()

    /** The bottom bar: setup steps first, then Dot's next pick. */
    private fun refreshNextBar() {
        if (!::nextBar.isInitialized) return
        val pick = Taste.nextPick()
        when {
            !MusicListener.enabled(this) -> showBar(R.string.bar_setup, getString(R.string.bar_setup_text)) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            !YouTubeMusic.installed(this) -> showBar(R.string.bar_get, getString(R.string.get_yt_music_note)) { YouTubeMusic.getIt(this) }
            pick != null -> showBar(if (DotPrefs.dj(this)) R.string.bar_next_dj else R.string.bar_next,
                if (pick.kind == TasteEngine.Kind.ARTIST) getString(R.string.artist_mix, pick.artist) else "${pick.title} — ${pick.artist}") {
                if (!YouTubeMusic.play(this, pick)) android.widget.Toast.makeText(this, R.string.yt_unreachable, android.widget.Toast.LENGTH_LONG).show()
            }
            MusicCenter.current == null -> showBar(R.string.bar_start, getString(R.string.bar_start_text)) { panel.show() }
            else -> showBar(R.string.bar_learning, getString(R.string.bar_learning_text)) { panel.show() }
        }
    }

    private fun showBar(label: Int, text: String, action: () -> Unit) {
        nextLabel.text = getString(label).uppercase()
        nextText.text = text
        nextAction = action
    }

    companion object {
        const val EXTRA_FOR_YOU = "for_you"
    }
}

package com.dotmusic.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The call screen: the contact's photo spinning as a record of dots, their name in dot type, the
 * timer counted in dots, and glass controls for answering, muting, the keypad, speaker, hold and
 * ending the call.
 */
class DotCallActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var record: DotRecord
    private lateinit var topBar: LinearLayout
    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var activeRow: LinearLayout
    private lateinit var ringingRow: LinearLayout
    private lateinit var mute: TextView
    private lateinit var dialpad: TextView
    private lateinit var speaker: TextView
    private lateinit var hold: TextView
    private lateinit var end: TextView
    private lateinit var answer: TextView
    private lateinit var decline: TextView
    private lateinit var keypad: LinearLayout
    private var padOpen = false

    private var number = ""
    private var name: String? = null
    private var art: Bitmap? = null
    private var lookedUp = ""
    private var signature = ""
    private var bottomInset = 0

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateCall()
            if (!isFinishing) handler.postDelayed(this, 1000L)
        }
    }
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateCall()
    }

    private fun dp(v: Float) = Glass.px(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(buildUi())
        updateCall()
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        root = FrameLayout(this)
        root.addView(AuraBackdrop(this), FrameLayout.LayoutParams(-1, -1))

        record = DotRecord(this).apply {
            topReserve = dp(84f).toFloat()
            bottomReserve = dp(250f).toFloat()
            accent = Glass.GREEN
            mono = Store.whiteDots(this@DotCallActivity)
        }
        root.addView(record, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), 0, dp(16f), 0)
        }
        topBar.addView(Glass.text(this, getString(R.string.call_title).uppercase(), 12f, Glass.MUTED, dot = true, spacing = 0.2f),
            LinearLayout.LayoutParams(0, -2, 1f))
        status = Glass.text(this, "", 11f, Glass.INK, dot = true, spacing = 0.1f).apply {
            gravity = Gravity.CENTER
            background = Glass.panel(this@DotCallActivity, 999f)
            setPadding(dp(14f), 0, dp(14f), 0)
        }
        topBar.addView(status, LinearLayout.LayoutParams(-2, dp(32f)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(52f), Gravity.TOP))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Glass.panel(this@DotCallActivity, 32f, strength = 1.3f)
            setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
        }

        activeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mute = circle(getString(R.string.mute), 0) {
            DotInCallService.service?.setMuted(!DotInCallService.muted)
        }
        dialpad = circle(getString(R.string.keypad), 1) { showKeypad(!padOpen) }
        speaker = circle(getString(R.string.speaker), 2) {
            DotInCallService.service?.setAudioRoute(
                if (DotInCallService.speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
            )
        }
        hold = circle(getString(R.string.hold), 3) {
            val call = DotInCallService.currentCall ?: return@circle
            if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
        }
        activeRow.addView(mute, LinearLayout.LayoutParams(0, dp(60f), 1f))
        activeRow.addView(dialpad, LinearLayout.LayoutParams(0, dp(60f), 1f))
        activeRow.addView(speaker, LinearLayout.LayoutParams(0, dp(60f), 1f))
        activeRow.addView(hold, LinearLayout.LayoutParams(0, dp(60f), 1f))
        panel.addView(activeRow, LinearLayout.LayoutParams(-1, -2))

        end = TextView(this).apply {
            text = getString(R.string.end_call).uppercase()
            typeface = Glass.doto(this@DotCallActivity)
            textSize = 13f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Glass.fill(this@DotCallActivity, Glass.RED, 26f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.end_call)
            setOnClickListener { Glass.tap(this); DotInCallService.currentCall?.disconnect() }
        }
        panel.addView(end, LinearLayout.LayoutParams(-1, dp(50f)).apply { topMargin = dp(10f) })

        ringingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        decline = pill(getString(R.string.decline), Glass.RED, Color.WHITE) {
            DotInCallService.currentCall?.disconnect()
        }
        answer = pill(getString(R.string.answer), Glass.GREEN, Color.rgb(4, 20, 13), filled = true) {
            DotInCallService.currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }
        ringingRow.addView(decline, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })
        ringingRow.addView(answer, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })
        panel.addView(ringingRow, LinearLayout.LayoutParams(-1, -2))

        keypad = buildKeypad()

        val bottom = FrameLayout(this)
        bottom.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        bottom.addView(keypad, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(10f), 0, dp(10f), 0)
        })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutout)
            @Suppress("DEPRECATION")
            bottomInset = insets.systemWindowInsetBottom
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = top
            (panel.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomInset + dp(12f)
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(dp(12f), 0, dp(12f), bottomInset + dp(12f))
            }
            (keypad.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomInset + dp(12f)
            topBar.requestLayout()
            panel.requestLayout()
            keypad.requestLayout()
            insets
        }
        return root
    }

    private fun pill(label: String, fill: Int, ink: Int, filled: Boolean = false, action: () -> Unit) =
        TextView(this).apply {
            text = label.uppercase()
            typeface = Glass.doto(this@DotCallActivity)
            textSize = 12f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setTextColor(ink)
            background = if (filled) Glass.fill(this@DotCallActivity, fill, 26f)
            else Glass.fill(this@DotCallActivity, Glass.withAlpha(fill, 200), 26f)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { Glass.tap(this); action() }
        }

    /** A round glass button with its label underneath, the player's control style. */
    private fun circle(label: String, index: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label.uppercase()
        typeface = Glass.doto(this@DotCallActivity)
        textSize = 9f
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        setTextColor(Glass.INK)
        background = Glass.panel(this@DotCallActivity, 999f)
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { Glass.tap(this); action() }
    }

    private fun buildKeypad(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.panel(this@DotCallActivity, 30f, strength = 1.4f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(12f))
            visibility = View.GONE
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(6f), dp(6f))
            addView(Glass.label(this@DotCallActivity, getString(R.string.keypad)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@DotCallActivity).apply {
                text = "✕"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Glass.MUTED)
                isClickable = true
                setOnClickListener { showKeypad(false) }
            }, LinearLayout.LayoutParams(dp(34f), dp(30f)))
        }, LinearLayout.LayoutParams(-1, -2))

        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowKeys.forEach { (digit, letters) ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = Glass.panel(this@DotCallActivity, 20f)
                    isClickable = true
                    isFocusable = true
                    addView(Glass.text(this@DotCallActivity, digit, 18f, Glass.INK))
                    if (letters.isNotEmpty()) addView(
                        Glass.text(this@DotCallActivity, letters, 7f, Glass.MUTED, dot = true, spacing = 0.12f)
                    )
                    setOnClickListener { tone(this, digit.first()) }
                }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return card
    }

    private fun tone(key: View, digit: Char) {
        Glass.tap(key)
        val call = DotInCallService.currentCall ?: return
        runCatching { call.playDtmfTone(digit) }
        handler.postDelayed({ runCatching { call.stopDtmfTone() } }, 160)
    }

    private fun showKeypad(open: Boolean) {
        padOpen = open
        keypad.visibility = if (open) View.VISIBLE else View.GONE
        panel.visibility = if (open) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- the call

    private fun updateCall() {
        val call = DotInCallService.currentCall
        if (call == null) { finish(); return }

        val handle = call.details.handle?.schemeSpecificPart.orEmpty()
        if (handle != number) {
            number = handle
            name = null
            art = null
            lookedUp = ""
            lookUp(handle)
        }

        val callState = call.state
        val incoming = callState == Call.STATE_RINGING
        val active = callState == Call.STATE_ACTIVE
        val held = callState == Call.STATE_HOLDING
        val seconds = if (active) {
            ((System.currentTimeMillis() - call.details.connectTimeMillis) / 1000L).coerceAtLeast(0L)
        } else 0L
        val stateText = when {
            incoming -> getString(R.string.incoming)
            active -> clock(seconds)
            held -> getString(R.string.on_hold)
            callState == Call.STATE_DIALING -> getString(R.string.calling)
            callState == Call.STATE_CONNECTING -> getString(R.string.connecting)
            callState == Call.STATE_DISCONNECTED || callState == Call.STATE_DISCONNECTING -> getString(R.string.ended)
            else -> getString(R.string.in_call)
        }
        status.text = stateText.uppercase()
        status.setTextColor(
            when {
                held -> Glass.AMBER
                incoming -> Glass.GREEN
                else -> Glass.INK
            }
        )

        val title = name?.takeIf { it.isNotBlank() } ?: number.ifBlank { getString(R.string.unknown_caller) }
        val accent = when {
            held -> Glass.AMBER
            active || incoming -> Glass.GREEN
            else -> Glass.BLUE
        }
        val playing = callState == Call.STATE_RINGING || callState == Call.STATE_DIALING ||
            callState == Call.STATE_CONNECTING || callState == Call.STATE_ACTIVE
        val now = "$title|${art?.hashCode()}|$playing|$accent"
        if (now != signature) {
            signature = now
            record.setTitle(title)
            record.setArt(art, animate = true)
            record.accent = accent
            record.playing = playing
        }

        val muted = DotInCallService.muted
        val loud = DotInCallService.speaker
        record.subtitle = when {
            incoming -> getString(R.string.answer_or_decline)
            held -> getString(R.string.on_hold)
            muted && loud -> "${getString(R.string.muted)} · ${getString(R.string.speaker)}"
            muted -> getString(R.string.muted)
            loud -> getString(R.string.speaker)
            else -> stateText
        }.uppercase()
        record.progress = ((seconds / 60f) / 34f).coerceIn(0f, 1f)

        paint(mute, muted, getString(R.string.unmute), getString(R.string.mute))
        paint(speaker, loud, getString(R.string.speaker), getString(R.string.speaker))
        paint(hold, held, getString(R.string.unhold), getString(R.string.hold))

        ringingRow.visibility = if (incoming) View.VISIBLE else View.GONE
        activeRow.visibility = if (incoming) View.GONE else View.VISIBLE
        end.visibility = if (incoming) View.GONE else View.VISIBLE
    }

    /** A round button that reads as switched on when its state is on. */
    private fun paint(button: TextView, on: Boolean, onLabel: String, offLabel: String) {
        button.text = (if (on) onLabel else offLabel).uppercase()
        button.background = if (on) Glass.fill(this, Color.WHITE, 999f) else Glass.panel(this, 999f)
        button.setTextColor(if (on) Color.rgb(8, 10, 12) else Glass.INK)
    }

    private fun clock(seconds: Long) = String.format("%02d:%02d", seconds / 60, seconds % 60)

    /** The contact's name and photo: Dot's own photo first, then the address book's. */
    private fun lookUp(handle: String) {
        if (handle.isBlank() || lookedUp == handle) return
        lookedUp = handle
        val key = Store.key(handle)
        ContactPhotos.load(this, key)?.let { art = it }
        if (art != null) {
            signature = ""
            updateCall()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        Thread {
            val person = Contacts.personFor(this, handle)
            val picture = person?.photoUri?.takeIf { it.isNotBlank() }
                ?.let { ContactPhotos.decode(this, Uri.parse(it)) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (person != null && name.isNullOrBlank()) name = person.name
                if (art == null && picture != null) art = picture
                signature = ""
                updateCall()
            }
        }.start()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DotInCallService.ACTION_CALL_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(changes, filter)
        }
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(changes) }
        super.onStop()
    }

    companion object {
        fun intent(context: Context) = Intent(context, DotCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}

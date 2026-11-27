package com.dotmusic.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The call screen, which is the app's full-screen dot player.
 *
 * The caller's photo becomes the spinning record of dots, their name rains in column by column in
 * the dot font, the ring around it works as an equaliser, and the row of dots underneath lights one
 * more dot for every minute of the call. The controls are the player's pill controls, answering,
 * declining, muting, switching to speaker and ending.
 */
class DotCallActivity : Activity() {
    private lateinit var player: DotPlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var statusChip: TextView
    private lateinit var ringingRow: LinearLayout
    private lateinit var activeRow: LinearLayout
    private lateinit var answer: TextView
    private lateinit var decline: TextView
    private lateinit var mute: TextView
    private lateinit var speaker: TextView
    private lateinit var end: TextView

    private var shownTrack = ""
    private var number = ""
    private var name: String? = null
    private var photo: Bitmap? = null
    private var lookedUp = ""
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateCall()
            if (!isFinishing) handler.postDelayed(this, 1000L)
        }
    }
    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateCall()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        player = DotPlayerView(this).apply {
            // The record is the call screen: the player's own play/pause/skip controls and gestures
            // would reach the music, so both are off here.
            stage.showControls = false
            stage.gestures = false
            stage.topReserve = dp(64f)
            stage.bottomReserve = dp(140f)
        }
        root.addView(player, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), 0, dp(16f), 0)
        }
        topBar.addView(TextView(this).apply {
            text = getString(R.string.call_title).uppercase()
            typeface = DotUi.doto(this@DotCallActivity)
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(Color.argb(190, 255, 255, 255))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        statusChip = TextView(this).apply {
            typeface = DotUi.doto(this@DotCallActivity)
            textSize = 12f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = chip(Color.argb(170, 18, 18, 18))
            setPadding(dp(14f), 0, dp(14f), 0)
        }
        topBar.addView(statusChip, LinearLayout.LayoutParams(-2, dp(34f)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(56f), Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18f), 0, dp(18f), dp(18f))
        }

        ringingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        decline = control(getString(R.string.call_decline), RED, Color.WHITE) {
            DotInCallService.currentCall?.disconnect()
        }
        answer = control(getString(R.string.call_answer), GREEN, Color.rgb(4, 17, 12)) {
            DotInCallService.currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }
        ringingRow.addView(decline, LinearLayout.LayoutParams(0, dp(60f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })
        ringingRow.addView(answer, LinearLayout.LayoutParams(0, dp(60f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })

        activeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mute = control(getString(R.string.call_mute), PILL, Color.WHITE) {
            DotInCallService.service?.setMuted(!DotInCallService.muted)
        }
        speaker = control(getString(R.string.call_speaker), PILL, Color.WHITE) {
            DotInCallService.service?.setAudioRoute(
                if (DotInCallService.speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
            )
        }
        end = control(getString(R.string.call_end), RED, Color.WHITE) {
            DotInCallService.currentCall?.disconnect()
        }
        activeRow.addView(mute, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { setMargins(dp(4f), 0, dp(4f), 0) })
        activeRow.addView(speaker, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { setMargins(dp(4f), 0, dp(4f), 0) })
        activeRow.addView(end, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { setMargins(dp(4f), 0, dp(4f), 0) })

        bottom.addView(ringingRow, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(activeRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutoutTop = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutoutTop)
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = top
            bottom.setPadding(dp(18f), 0, dp(18f), bottomInset + dp(16f))
            topBar.requestLayout()
            bottom.requestLayout()
            insets
        }
        return root
    }

    private fun control(label: String, fill: Int, ink: Int, action: () -> Unit) = TextView(this).apply {
        text = label.uppercase()
        typeface = DotUi.doto(this@DotCallActivity)
        textSize = 12f
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        setTextColor(ink)
        background = DotUi.pill(this@DotCallActivity, fill, 26f)
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun chip(fill: Int) = GradientDrawable().apply {
        cornerRadius = dp(17f).toFloat()
        setColor(fill)
        setStroke(dp(1f), Color.argb(40, 255, 255, 255))
    }

    /** A pill that reads as switched on: white fill, black dots. */
    private fun paint(button: TextView, on: Boolean) {
        button.background = DotUi.pill(this, if (on) Color.WHITE else PILL, 26f)
        button.setTextColor(if (on) Color.rgb(8, 9, 11) else Color.WHITE)
    }

    // ---------------------------------------------------------------- the call

    private fun updateCall() {
        val call = DotInCallService.currentCall
        if (call == null) { finish(); return }

        val handle = call.details.handle?.schemeSpecificPart.orEmpty()
        if (handle != number) {
            number = handle
            name = null
            photo = null
            lookedUp = ""
            lookUpCaller(handle)
        }

        val state = call.state
        val incoming = state == Call.STATE_RINGING
        val active = state == Call.STATE_ACTIVE
        val elapsed = if (active) {
            ((System.currentTimeMillis() - call.details.connectTimeMillis) / 1000L).coerceAtLeast(0L)
        } else 0L
        val label = when {
            incoming -> getString(R.string.call_incoming)
            active -> clock(elapsed)
            state == Call.STATE_DIALING -> getString(R.string.call_calling)
            state == Call.STATE_CONNECTING -> getString(R.string.call_connecting)
            state == Call.STATE_HOLDING -> getString(R.string.call_on_hold)
            state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING -> getString(R.string.call_ended)
            else -> getString(R.string.call_in_call)
        }
        statusChip.text = label.uppercase()

        val title = name?.takeIf { it.isNotBlank() } ?: number.ifBlank { getString(R.string.call_unknown) }
        val accent = when {
            incoming -> RED
            active -> GREEN
            else -> Color.WHITE
        }
        val lively = state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING &&
            state != Call.STATE_HOLDING
        val signature = "$title|${photo?.hashCode()}|$accent|$lively"
        if (signature != shownTrack) {
            shownTrack = signature
            player.setTrack(Track(
                title = title,
                artist = "",
                packageName = CALL_PACKAGE,
                art = photo,
                accent = accent,
                isPlaying = lively,
                positionMs = 0L,
                positionUpdatedAt = 0L,
                speed = 1f,
                durationMs = 0L,
                openApp = null
            ))
        }

        val muted = DotInCallService.muted
        val loud = DotInCallService.speaker
        player.stage.subtitle = when {
            incoming -> getString(R.string.call_answer_or_decline)
            muted && loud -> "${getString(R.string.call_muted)} · ${getString(R.string.call_speaker)}"
            muted -> getString(R.string.call_muted)
            loud -> getString(R.string.call_speaker)
            else -> label
        }.uppercase()
        // One dot per minute on the line.
        player.stage.progressOverride = ((elapsed / 60f) / 34f).coerceIn(0f, 1f)

        ringingRow.visibility = if (incoming) View.VISIBLE else View.GONE
        activeRow.visibility = if (incoming) View.GONE else View.VISIBLE
        if (active) decline.text = getString(R.string.call_end).uppercase()
        mute.text = (if (muted) getString(R.string.call_unmute) else getString(R.string.call_mute)).uppercase()
        paint(mute, muted)
        speaker.text = getString(R.string.call_speaker).uppercase()
        paint(speaker, loud)
    }

    private fun clock(seconds: Long) = String.format("%02d:%02d", seconds / 60, seconds % 60)

    /** The caller's name and photo, if the address book has them. */
    private fun lookUpCaller(handle: String) {
        if (handle.isBlank() || lookedUp == handle) return
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        lookedUp = handle
        Thread {
            var found: String? = null
            var picture: Bitmap? = null
            runCatching {
                contentResolver.query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(handle)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.CONTACT_ID),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        found = cursor.getString(0)
                        val id = cursor.getLong(1)
                        val contact = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
                        contentResolver.openContactPhotoInputStream(contact, true)?.use { stream ->
                            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                            picture = BitmapFactory.decodeStream(stream, null, options)
                        }
                    }
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                name = found
                photo = picture
                shownTrack = ""
                updateCall()
            }
        }.start()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DotInCallService.ACTION_CALL_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(callReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(callReceiver, filter)
        }
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(callReceiver) }
        super.onStop()
    }

    companion object {
        /** Not a music package: the stage only uses this to tell one "song" from the next. */
        private const val CALL_PACKAGE = "com.dotmusic.app.call"
        private val RED = Color.rgb(229, 83, 74)
        private val GREEN = Color.rgb(102, 232, 161)
        private val PILL = Color.rgb(26, 28, 31)

        fun intent(context: Context) = Intent(context, DotCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}

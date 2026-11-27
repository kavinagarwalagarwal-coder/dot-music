package com.dotmusic.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Dot's native full-screen surface for incoming and active carrier calls. */
class DotCallActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var number: TextView
    private lateinit var answer: TextView
    private lateinit var decline: TextView
    private lateinit var mute: TextView
    private lateinit var speaker: TextView
    private var muted = false
    private var loudspeaker = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
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
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        updateCall()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28f), dp(24f), dp(28f), dp(30f))
            setBackgroundColor(Color.rgb(5, 6, 8))
        }
        root.addView(TextView(this).apply {
            text = "DOT  /  PHONE"; textSize = 12f; letterSpacing = .18f
            typeface = doto(); setTextColor(Color.rgb(150, 158, 165)); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(36f)))
        root.addView(CallDotView(this), LinearLayout.LayoutParams(dp(300f), dp(300f)).apply {
            gravity = Gravity.CENTER; topMargin = dp(10f); bottomMargin = dp(18f)
        })
        number = TextView(this).apply {
            textSize = 28f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2
        }
        root.addView(number, LinearLayout.LayoutParams(-1, dp(72f)))
        status = TextView(this).apply {
            textSize = 13f; letterSpacing = .1f; setTextColor(Color.rgb(102, 232, 161)); gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(36f)))

        val utility = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mute = control("MUTE", Color.rgb(34, 37, 41)) { muted = !muted; DotInCallService.service?.setMuted(muted); mute.text = if (muted) "UNMUTE" else "MUTE" }
        speaker = control("SPEAKER", Color.rgb(34, 37, 41)) {
            loudspeaker = !loudspeaker
            DotInCallService.service?.setAudioRoute(if (loudspeaker) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE)
            speaker.text = if (loudspeaker) "PHONE" else "SPEAKER"
        }
        utility.addView(mute, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })
        utility.addView(speaker, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })
        root.addView(utility, LinearLayout.LayoutParams(-1, dp(60f)).apply { topMargin = dp(18f) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        answer = control("ANSWER", Color.rgb(102, 232, 161)) { DotInCallService.currentCall?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) }
        answer.setTextColor(Color.rgb(4, 17, 12))
        decline = control("END CALL", Color.rgb(229, 83, 74)) { DotInCallService.currentCall?.disconnect() }
        decline.setTextColor(Color.WHITE)
        actions.addView(answer, LinearLayout.LayoutParams(0, dp(60f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })
        actions.addView(decline, LinearLayout.LayoutParams(0, dp(60f), 1f).apply { setMargins(dp(5f), 0, dp(5f), 0) })
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(68f)).apply { topMargin = dp(14f) })
        return root
    }

    private fun control(label: String, color: Int, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; letterSpacing = .07f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE); background = DotUi.pill(this@DotCallActivity, color, 28f)
        isClickable = true; isFocusable = true; contentDescription = label
        setOnClickListener { action() }
    }

    private fun updateCall() {
        val call = DotInCallService.currentCall
        if (call == null) { finish(); return }
        val callNumber = call.details.handle?.schemeSpecificPart.orEmpty().ifBlank { "Unknown caller" }
        number.text = callNumber
        val incoming = call.state == Call.STATE_RINGING
        val elapsed = if (call.state == Call.STATE_ACTIVE) {
            val seconds = ((System.currentTimeMillis() - call.details.connectTimeMillis) / 1000L).coerceAtLeast(0)
            String.format("%02d:%02d", seconds / 60, seconds % 60)
        } else null
        status.text = when {
            incoming -> "INCOMING CALL"
            elapsed != null -> elapsed
            call.state == Call.STATE_CONNECTING || call.state == Call.STATE_DIALING -> "CONNECTING"
            call.state == Call.STATE_HOLDING -> "ON HOLD"
            else -> "CALL"
        }
        answer.visibility = if (incoming) View.VISIBLE else View.GONE
        mute.visibility = if (incoming) View.GONE else View.VISIBLE
        speaker.visibility = if (incoming) View.GONE else View.VISIBLE
        decline.text = if (incoming) "DECLINE" else "END CALL"
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DotInCallService.ACTION_CALL_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(callReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(callReceiver, filter)
        }
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        runCatching { unregisterReceiver(callReceiver) }
        super.onStop()
    }

    private fun doto(): Typeface = runCatching {
        Typeface.Builder(assets, "fonts/Doto.ttf").setFontVariationSettings("'wght' 800, 'ROND' 100").build()
    }.getOrDefault(Typeface.MONOSPACE)

    companion object {
        fun intent(context: Context) = Intent(context, DotCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}

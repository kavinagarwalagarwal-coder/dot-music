package com.dotmusic.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Full-screen dot player. Opens from the widgets' record or title, and from the app. */
class DotPlayerActivity : Activity(), MusicCenter.Observer {
    private lateinit var view: DotPlayerView
    private var visualizer: Visualizer? = null
    private val peaks = FloatArray(48) { 0.05f }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        view = DotPlayerView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        MusicCenter.attach(this)
        MusicCenter.observe(this)
        view.start()
        startBeat()
    }

    override fun onPause() {
        MusicCenter.forget(this)
        view.stop()
        stopBeat()
        super.onPause()
    }

    override fun onTrack(track: Track?) {
        view.setTrack(track, view.takeDirection())
        // A now-playing screen: stay awake while the music plays.
        if (track?.isPlaying == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Real beat reaction: Android's visualizer measures how loud each frequency band is in what's
     * playing. It needs the microphone permission by Android's rules, but records nothing.
     */
    private fun startBeat() {
        if (!DotPrefs.beat(this) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        visualizer = runCatching {
            Visualizer(0).apply {
                captureSize = min(512, Visualizer.getCaptureSizeRange()[1])
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { fft?.let(::analyse) }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        }.getOrNull()
    }

    private fun stopBeat() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        view.spectrum = null
    }

    /** FFT → 48 log-spaced bands, each scaled by its own recent peak so any volume works. */
    private fun analyse(fft: ByteArray) {
        val bins = fft.size / 2
        val out = FloatArray(peaks.size)
        val lo = 1.0
        val hi = (bins - 1).toDouble()
        for (b in peaks.indices) {
            val from = (lo * (hi / lo).pow(b.toDouble() / peaks.size)).toInt().coerceIn(1, bins - 1)
            val to = max(from + 1, (lo * (hi / lo).pow((b + 1).toDouble() / peaks.size)).toInt().coerceIn(1, bins))
            var sum = 0f
            for (k in from until to) sum += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            val level = ln(1f + sum / (to - from))
            peaks[b] = max(level, peaks[b] * 0.995f).coerceAtLeast(0.05f)
            out[b] = (level / peaks[b]).coerceIn(0f, 1f)
        }
        view.spectrum = out
    }
}

package com.dotmusic.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Real beat reaction: Android's visualizer measures how loud each frequency band is in what's
 * playing. Android files it under the microphone permission, but nothing is ever recorded.
 * Hands 48 log-spaced band levels (0..1) to [onBands].
 */
class BeatListener(private val context: Context, private val onBands: (FloatArray?) -> Unit) {
    private var visualizer: Visualizer? = null
    private val peaks = FloatArray(48) { 0.05f }

    fun start() {
        if (visualizer != null || !DotPrefs.beat(context)) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
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

    fun stop() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        onBands(null)
    }

    /** FFT → 48 log-spaced bands, each scaled by its own recent peak so any volume works. */
    private fun analyse(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins < 4) return
        val out = FloatArray(peaks.size)
        val hi = (bins - 1).toDouble()
        for (b in peaks.indices) {
            val from = hi.pow(b.toDouble() / peaks.size).toInt().coerceIn(1, bins - 1)
            val to = max(from + 1, hi.pow((b + 1).toDouble() / peaks.size).toInt().coerceIn(1, bins))
            var sum = 0f
            for (k in from until to) sum += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            val level = ln(1f + sum / (to - from))
            peaks[b] = max(level, peaks[b] * 0.995f).coerceAtLeast(0.05f)
            out[b] = (level / peaks[b]).coerceIn(0f, 1f)
        }
        onBands(out)
    }
}

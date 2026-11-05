package com.dotmusic.app

import android.app.PendingIntent
import android.graphics.Bitmap
import android.os.SystemClock

/** What's playing right now, as the widget and player need it. */
data class Track(
    val title: String,
    val artist: String,
    val packageName: String,
    val art: Bitmap?,
    /** Main colour of the cover, for accents. */
    val accent: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val durationMs: Long,
    val openApp: PendingIntent?,
) {
    /** Same song (ignoring play state and position). */
    val id: String get() = "$packageName|$title|$artist"

    fun livePositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) positionMs + ((now - positionUpdatedAt) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    fun progress(now: Long = SystemClock.elapsedRealtime()): Float =
        if (durationMs > 0) livePositionMs(now).toFloat() / durationMs else 0f
}

package com.dotmusic.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/** The three dot music widgets. They share one renderer; only the layout differs. */
abstract class DotWidget(val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        MusicCenter.attach(context)
        MusicWidgets.render(context, this.javaClass, layout, ids, MusicCenter.current, full = true)
        MusicCenter.scheduleTick()
    }

    override fun onEnabled(context: Context) = MusicCenter.scheduleTick()
}

class SmallDotWidget : DotWidget(R.layout.widget_small)
class MediumDotWidget : DotWidget(R.layout.widget_medium)
class WideDotWidget : DotWidget(R.layout.widget_wide)

/** Play/pause, next and previous from a widget, sent to the playing app's media session. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = runCatching { MusicCenter.Command.valueOf(intent.action ?: return) }.getOrNull() ?: return
        MusicCenter.command(command)
    }
}

object MusicWidgets {
    private val kinds = listOf(
        SmallDotWidget::class.java to R.layout.widget_small,
        MediumDotWidget::class.java to R.layout.widget_medium,
        WideDotWidget::class.java to R.layout.widget_wide,
    )

    // Pictures are drawn once per song (and style) and reused for every widget and update.
    private var cachedFor: String? = null
    private var disc: Bitmap? = null
    private var title: Bitmap? = null
    private var titleWide: Bitmap? = null

    private fun ids(context: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))

    fun hasAny(context: Context) = kinds.any { ids(context, it.first).isNotEmpty() }

    fun refresh(context: Context) {
        val track = MusicCenter.current
        for ((cls, layout) in kinds) {
            val ids = ids(context, cls)
            if (ids.isNotEmpty()) render(context, cls, layout, ids, track, full = true)
        }
    }

    /** Only the dot progress bar and clock: a tiny update every few seconds while playing. */
    fun refreshProgress(context: Context) {
        val track = MusicCenter.current ?: return
        val manager = AppWidgetManager.getInstance(context)
        val ids = ids(context, MediumDotWidget::class.java)
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_medium)
        progress(views, track)
        runCatching { manager.partiallyUpdateAppWidget(ids, views) }
    }

    internal fun render(context: Context, cls: Class<*>, layout: Int, ids: IntArray, track: Track?, full: Boolean) {
        val views = build(context, layout, track)
        val manager = AppWidgetManager.getInstance(context)
        runCatching { if (full) manager.updateAppWidget(ids, views) else manager.partiallyUpdateAppWidget(ids, views) }
    }

    /** One widget's picture for [track] (or the resting state when nothing is playing). */
    fun build(context: Context, layout: Int, track: Track?): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val mono = DotPrefs.mono(context)
        prepare(context, track, mono)
        val playing = track?.isPlaying == true
        val animate = playing && DotPrefs.animations(context)

        if (layout != R.layout.widget_wide) views.setImageViewBitmap(R.id.art, disc)
        when (layout) {
            R.layout.widget_wide -> titleWide?.let { views.setImageViewBitmap(R.id.title, it) }
            R.layout.widget_medium -> title?.let { views.setImageViewBitmap(R.id.title, it) }
        }
        views.setTextViewText(R.id.artist, track?.artist?.ifBlank { null } ?: context.getString(
            if (track == null) R.string.widget_idle_hint else R.string.app_name))

        // The launcher plays these animations itself; we only switch them on and off.
        views.setViewVisibility(R.id.orbit, if (animate) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.pulse, if (animate) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.eq, if (animate) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.eq_still, if (animate) View.GONE else View.VISIBLE)

        views.setImageViewResource(R.id.play, if (playing) R.drawable.ic_dot_pause else R.drawable.ic_dot_play)
        views.setOnClickPendingIntent(R.id.play, action(context, MusicCenter.Command.TOGGLE))
        views.setOnClickPendingIntent(R.id.next, action(context, MusicCenter.Command.NEXT))
        views.setOnClickPendingIntent(R.id.prev, action(context, MusicCenter.Command.PREVIOUS))
        // The record and the title open the full-screen dot player.
        val player = PendingIntent.getActivity(context, 1,
            Intent(context, DotPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.art, player)
        views.setOnClickPendingIntent(R.id.title, player)
        if (layout != R.layout.widget_small) views.setOnClickPendingIntent(R.id.root, player)

        if (layout == R.layout.widget_medium) progress(views, track)
        return views
    }

    private fun progress(views: RemoteViews, track: Track?) {
        val accent = track?.accent ?: MusicListener.DEFAULT_ACCENT
        views.setImageViewBitmap(R.id.progress, DotArt.progress(track?.progress() ?: 0f, 18, 9f, accent))
        val pos = track?.livePositionMs() ?: 0L
        views.setChronometer(R.id.time, SystemClock.elapsedRealtime() - pos, null, track?.isPlaying == true)
        views.setViewVisibility(R.id.time, if (track == null) View.GONE else View.VISIBLE)
    }

    private fun prepare(context: Context, track: Track?, mono: Boolean) {
        val key = "${track?.id}|${track?.art?.generationId}|$mono"
        if (key == cachedFor && disc != null) return
        cachedFor = key
        val src = track?.art
        disc = DotArt.cover(src, 23, 230, mono, disc = true)
        val label = track?.title ?: context.getString(R.string.widget_idle)
        val color = if (track == null) Color.argb(110, 255, 255, 255) else Color.WHITE
        title = DotArt.text(label.uppercase(), 9, 5f, color, 64)
        titleWide = DotArt.text(label.uppercase(), 9, 5f, color, 80)
    }

    private fun action(context: Context, c: MusicCenter.Command): PendingIntent =
        PendingIntent.getBroadcast(context, c.ordinal + 10,
            Intent(context, WidgetActionReceiver::class.java).setAction(c.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

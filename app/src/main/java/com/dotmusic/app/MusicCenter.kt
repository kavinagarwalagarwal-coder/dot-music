package com.dotmusic.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * The song playing right now, shared by the widgets and the full-screen player.
 * Fed by [MusicListener]; controls go back to the playing app's media session.
 */
object MusicCenter {
    fun interface Observer { fun onTrack(track: Track?) }

    enum class Command { TOGGLE, NEXT, PREVIOUS }

    private val main = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<Observer>()
    private var app: Context? = null

    var current: Track? = null
        private set

    /** Set by [MusicListener] while it is connected. */
    internal var controls: ((Command) -> Unit)? = null
    internal var seeker: ((Long) -> Unit)? = null

    val connected: Boolean get() = controls != null

    fun attach(context: Context) { app = context.applicationContext }

    fun observe(o: Observer) { observers += o; o.onTrack(current) }
    fun forget(o: Observer) { observers -= o }

    fun command(c: Command) { controls?.invoke(c) }
    fun seekTo(ms: Long) { seeker?.invoke(ms) }

    internal fun publish(track: Track?) { main.post { deliver(track) } }

    private fun deliver(track: Track?) {
        val before = current
        current = track
        // Only re-render widgets when something they show changed.
        if (before?.id != track?.id || before?.isPlaying != track?.isPlaying || (before == null) != (track == null) ||
            before?.art !== track?.art) app?.let { MusicWidgets.refresh(it) }
        else app?.let { MusicWidgets.refreshProgress(it) }
        observers.toList().forEach { it.onTrack(track) }
        scheduleTick()
    }

    // The widgets' dot progress bar steps forward on its own while a song plays.
    private val tick = Runnable {
        app?.let { MusicWidgets.refreshProgress(it) }
        scheduleTick()
    }

    fun scheduleTick() {
        main.removeCallbacks(tick)
        val ctx = app ?: return
        if (current?.isPlaying != true || !MusicWidgets.hasAny(ctx)) return
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive == false) return
        main.postDelayed(tick, PROGRESS_TICK_MS)
    }

    private const val PROGRESS_TICK_MS = 6_000L
}

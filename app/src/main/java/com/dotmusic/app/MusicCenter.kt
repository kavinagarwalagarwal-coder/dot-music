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
    /** Asks an app's session to play from a search (package, query, extras); false if it isn't there or can't. */
    internal var searcher: ((String, String, android.os.Bundle) -> Boolean)? = null
    /** What an app's media session supports, for the settings check. */
    internal var inspector: ((String) -> SessionInfo?)? = null
    internal var sessionLister: (() -> List<String>)? = null

    data class SessionInfo(val actions: Long, val hasTitle: Boolean, val hasArt: Boolean, val hasQueue: Boolean)
    internal var queueReader: (() -> List<QueueEntry>)? = null
    internal var queueJumper: ((Long) -> Unit)? = null

    /** A song in the playing app's own queue. */
    data class QueueEntry(val id: Long, val title: String, val subtitle: String)

    val connected: Boolean get() = controls != null

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        Taste.attach(context)
    }

    fun observe(o: Observer) { observers += o; o.onTrack(current) }
    fun forget(o: Observer) { observers -= o }

    fun command(c: Command) {
        if (c == Command.NEXT) {
            Taste.noteSkip()
            // "Dot picks the next song": skip to Dot's pick instead of the app's queue.
            val ctx = app
            if (ctx != null && DotPrefs.dj(ctx) && PlayerApp.isPlayingThere(ctx)) {
                val pick = Taste.nextPick()
                if (pick != null && PlayerApp.playInSession(ctx, pick)) return
            }
        }
        controls?.invoke(c)
    }

    fun playFromSearch(pkg: String, query: String, extras: android.os.Bundle): Boolean = searcher?.invoke(pkg, query, extras) == true
    fun sessionInfo(pkg: String): SessionInfo? = inspector?.invoke(pkg)
    /** Apps with a media session right now. */
    fun sessionPackages(): List<String> = sessionLister?.invoke().orEmpty()

    /** Up next in the playing app, if it shares its queue. */
    fun queue(): List<QueueEntry> = queueReader?.invoke().orEmpty()
    fun jumpTo(id: Long) { queueJumper?.invoke(id) }
    fun seekTo(ms: Long) { seeker?.invoke(ms) }

    internal fun publish(track: Track?) { main.post { deliver(track) } }

    private fun deliver(track: Track?) {
        val before = current
        current = track
        // Only re-render widgets when something they show changed.
        if (before?.id != track?.id || before?.isPlaying != track?.isPlaying || (before == null) != (track == null) ||
            before?.art !== track?.art) app?.let { MusicWidgets.refresh(it) }
        else app?.let { MusicWidgets.refreshProgress(it) }
        Taste.onTrack(track)
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

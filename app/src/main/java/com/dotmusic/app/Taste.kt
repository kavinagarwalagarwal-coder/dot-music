package com.dotmusic.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Watches what plays (through [MusicCenter]), writes each finished listen to a private file, and
 * keeps fresh recommendations from [TasteEngine] ready for the player. Nothing leaves the phone.
 */
object Taste {
    fun interface Observer { fun onShelves(shelves: TasteEngine.Shelves) }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dot-taste").apply { priority = Thread.MIN_PRIORITY } }
    private val observers = LinkedHashSet<Observer>()
    private var app: Context? = null

    /** Loaded on [io] only. */
    private var listens: MutableList<Listen>? = null

    /** The latest recommendations, for the player and for "Dot picks the next song". */
    @Volatile var shelves: TasteEngine.Shelves? = null
        private set

    private class Open(val track: Track, val startedAt: Long) {
        var listened = 0L
        var mark = SystemClock.elapsedRealtime()
        var playing = track.isPlaying
        var duration = track.durationMs
    }
    private var open: Open? = null
    private var skipAt = 0L

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        refresh()
    }

    fun observe(o: Observer) { observers += o; shelves?.let(o::onShelves) }
    fun forget(o: Observer) { observers -= o }

    /** Next or a flick: if the song changes right after, it was a skip. */
    fun noteSkip() { skipAt = SystemClock.elapsedRealtime() }

    /** Every update of what's playing, on the main thread. */
    fun onTrack(track: Track?) {
        val ctx = app ?: return
        val now = SystemClock.elapsedRealtime()
        val o = open
        if (o != null) {
            if (o.playing) o.listened += (now - o.mark).coerceIn(0, MAX_SEGMENT_MS)
            o.mark = now
        }
        if (o != null && track != null && o.track.id == track.id) {
            o.playing = track.isPlaying
            if (track.durationMs > 0) o.duration = track.durationMs
            return
        }
        if (o != null) close(ctx, o, skipped = now - skipAt < SKIP_WINDOW_MS)
        open = track?.let { Open(it, System.currentTimeMillis()) }
        refresh()
    }

    private fun close(ctx: Context, o: Open, skipped: Boolean) {
        if (!DotPrefs.learn(ctx)) return
        if (o.listened < MIN_LISTEN_MS && !skipped) return
        val cal = Calendar.getInstance().apply { timeInMillis = o.startedAt }
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val l = Listen(
            startedAt = o.startedAt, title = o.track.title, artist = o.track.artist, source = o.track.packageName,
            durationMs = o.duration, listenedMs = o.listened, skipped = skipped,
            hour = cal.get(Calendar.HOUR_OF_DAY), weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY,
        )
        io.execute {
            val list = load(ctx)
            list += l
            runCatching { file(ctx).appendText(l.encode() + "\n") }
            if (list.size > MAX_KEPT + 2_000) compact(ctx, list)
        }
    }

    /** Recompute the shelves in the background, for the song playing now. */
    fun refresh() {
        val ctx = app ?: return
        val playing = MusicCenter.current
        io.execute {
            val list = load(ctx)
            val engine = TasteEngine(list.toList(), System.currentTimeMillis())
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val s = engine.shelves(playing?.title, playing?.artist, hour)
            shelves = s
            main.post { observers.toList().forEach { it.onShelves(s) } }
        }
    }

    /** "Dot picks the next song": the best next pick that isn't what's playing. */
    fun nextPick(): TasteEngine.Pick? {
        val cur = MusicCenter.current?.let { SongKeys.key(it.title, it.artist) }
        return shelves?.upNext?.firstOrNull { it.key != cur }
    }

    fun count(): Int = shelves?.listens ?: 0

    fun clear(context: Context) {
        val ctx = context.applicationContext
        io.execute {
            listens = ArrayList()
            runCatching { file(ctx).delete() }
        }
        open = null
        refresh()
    }

    private fun load(ctx: Context): MutableList<Listen> {
        listens?.let { return it }
        val list = ArrayList<Listen>()
        runCatching { file(ctx).takeIf { it.exists() }?.forEachLine { line -> Listen.decode(line)?.let(list::add) } }
        if (list.size > MAX_KEPT) compact(ctx, list)
        listens = list
        return list
    }

    private fun compact(ctx: Context, list: MutableList<Listen>) {
        val keep = list.takeLast(MAX_KEPT)
        list.clear(); list.addAll(keep)
        runCatching {
            val tmp = File(ctx.filesDir, "listens.tmp")
            tmp.writeText(keep.joinToString("") { it.encode() + "\n" })
            tmp.renameTo(file(ctx))
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "listens.tsv")

    private const val MIN_LISTEN_MS = 2_000L
    private const val SKIP_WINDOW_MS = 4_000L
    /** A gap longer than this between updates isn't trusted as listening. */
    private const val MAX_SEGMENT_MS = 15 * 60_000L
    private const val MAX_KEPT = 8_000
}

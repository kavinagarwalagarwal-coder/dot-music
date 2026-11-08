package com.dotmusic.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors

/**
 * Dot's own player. One queue of [OnlineTrack]s from YouTube, Audius and Jamendo:
 *  - Audius and Jamendo stream through ExoPlayer, kept alive by [PlaybackService], so they play in
 *    the background with lock-screen and notification controls.
 *  - YouTube plays in YouTube's official embedded player ([YouTubeEmbed]) on the player screen, so
 *    it only plays while Dot is on screen; it pauses when you leave and picks up when you're back.
 * When the queue runs out, the recommendation engine fills it (songs Dot can play without a
 * search first; off screen, only sources that can play in the background).
 * What's playing is published to [MusicCenter] like any app, so the dot player, widgets,
 * wallpaper and listening history all just work.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object DotPlayback : YouTubeEmbed.Listener {
    /** Track.packageName for Dot's own player: "dot:youtube", "dot:audius", "dot:jamendo". */
    const val SOURCE_PREFIX = "dot:"

    fun interface QueueObserver { fun onQueueChanged() }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var exo: ExoPlayer? = null
    private var embed: YouTubeEmbed? = null
    private val queueObservers = LinkedHashSet<QueueObserver>()

    private val queue = ArrayList<OnlineTrack>()
    var index = -1
        private set
    val current: OnlineTrack? get() = queue.getOrNull(index)
    fun upcoming(): List<OnlineTrack> = if (index < 0) emptyList() else queue.drop(index + 1)

    private var art: Bitmap? = null
    private var accent = MusicListener.DEFAULT_ACCENT
    private var loading = false
    private var filling = false
    private var failures = 0

    // YouTube's state, as its player reports it.
    private var ytPlaying = false
    private var ytWanted = false
    private var ytPos = 0L
    private var ytDur = 0L
    private var ytAt = 0L

    fun attach(context: Context) { if (app == null) app = context.applicationContext }

    fun observeQueue(o: QueueObserver) { queueObservers += o }
    fun forgetQueue(o: QueueObserver) { queueObservers -= o }
    private fun queueChanged() = queueObservers.toList().forEach { it.onQueueChanged() }

    /** The ExoPlayer, created on first use (the playback service wraps it in a media session). */
    fun player(context: Context): ExoPlayer = exo ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { it.addListener(exoListener); exo = it }

    internal fun releasePlayer() {
        exo?.run { removeListener(exoListener); release() }
        exo = null
    }

    // ---------------------------------------------------------------- queue

    /** Play [track] now; [upcoming] follows it, then Dot's picks. */
    fun play(context: Context, track: OnlineTrack, upcoming: List<OnlineTrack> = emptyList()) {
        attach(context)
        queue.clear()
        queue += track
        queue += upcoming.filter { it.key != track.key }
        index = 0
        failures = 0
        start()
    }

    fun playNext(context: Context, track: OnlineTrack) {
        if (current == null) return play(context, track)
        queue.add(index + 1, track)
        queueChanged()
    }

    fun enqueue(context: Context, tracks: List<OnlineTrack>) {
        if (current == null && tracks.isNotEmpty()) return play(context, tracks.first(), tracks.drop(1))
        queue.addAll(tracks)
        queueChanged()
    }

    /** Jump to the [n]th upcoming song. */
    fun jumpUpcoming(n: Int) {
        val i = index + 1 + n
        if (i in queue.indices) { index = i; start() }
    }

    fun stop() {
        exo?.stop()
        embed?.stop()
        queue.clear(); index = -1
        ytPlaying = false; ytWanted = false
        MusicCenter.publishOwn(null)
        queueChanged()
    }

    // ---------------------------------------------------------------- controls (via MusicCenter)

    fun command(c: MusicCenter.Command) = when (c) {
        MusicCenter.Command.TOGGLE -> toggle()
        MusicCenter.Command.NEXT -> next()
        MusicCenter.Command.PREVIOUS -> previous()
    }

    fun toggle() {
        val t = current ?: return
        if (t.source == OnlineTrack.Source.YOUTUBE) {
            ytWanted = !(ytPlaying || ytWanted)
            embed?.let { if (ytWanted) it.play() else it.pause() }
            if (embed == null && ytWanted) toast(R.string.yt_needs_screen)
            publish()
        } else {
            val p = exo ?: return start()
            when {
                p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED -> start()
                p.playWhenReady -> p.pause()
                else -> p.play()
            }
        }
    }

    fun next() {
        if (index + 1 < queue.size) { index++; start() } else autoplay(advance = true)
    }

    fun previous() {
        if (positionMs() > 3_000 || index <= 0) seekTo(0) else { index--; start() }
    }

    fun seekTo(ms: Long) {
        val t = current ?: return
        if (t.source == OnlineTrack.Source.YOUTUBE) { ytPos = ms; ytAt = SystemClock.elapsedRealtime(); embed?.seekTo(ms) }
        else exo?.seekTo(ms)
        publish()
    }

    private fun positionMs(): Long {
        val t = current ?: return 0
        return if (t.source == OnlineTrack.Source.YOUTUBE)
            ytPos + if (ytPlaying) SystemClock.elapsedRealtime() - ytAt else 0
        else exo?.currentPosition ?: 0
    }

    // ---------------------------------------------------------------- playing

    private fun start() {
        val ctx = app ?: return
        val t = current ?: return
        art = null
        accent = MusicListener.DEFAULT_ACCENT
        ArtLoader.load(t.artUrl, 480) { b ->
            if (current !== t || b == null) return@load
            art = square(b, t.source == OnlineTrack.Source.YOUTUBE)
            accent = accentOf(art!!)
            publish()
        }
        ytPos = 0; ytDur = 0; ytAt = SystemClock.elapsedRealtime(); ytPlaying = false

        if (t.source == OnlineTrack.Source.YOUTUBE) {
            exo?.stop()
            loading = false
            ytWanted = true
            val e = embed
            if (e != null) e.load(t.id) else toast(R.string.yt_needs_screen)
        } else {
            embed?.stop()
            ytWanted = false
            loading = true
            io.execute {
                val url = Catalogs.streamUrl(ctx, t)
                main.post {
                    if (current !== t) return@post
                    loading = false
                    if (url.isNullOrBlank()) return@post failed(t, "no stream")
                    val p = player(ctx)
                    p.setMediaItem(MediaItem.Builder()
                        .setMediaId(t.mediaId)
                        .setUri(url)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(t.title).setArtist(t.artist)
                            .setArtworkUri(t.artUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                            .build())
                        .build())
                    p.prepare()
                    p.play()
                    runCatching { ctx.startService(Intent(ctx, PlaybackService::class.java)) }
                    publish()
                }
            }
        }
        publish()
        queueChanged()
        if (upcoming().isEmpty()) autoplay(advance = false)
    }

    private fun failed(t: OnlineTrack, why: String) {
        failures++
        toast(R.string.play_failed, t.title, why)
        if (failures < 4) next() else { failures = 0; publish() }
    }

    /** Out of songs: add Dot's next pick (and play it, if [advance]). */
    private fun autoplay(advance: Boolean) {
        val ctx = app ?: return
        if (filling) return
        filling = true
        val recent = queue.takeLast(40).mapTo(HashSet()) { it.key }
        val canYouTube = embed != null && Catalogs.youtubeOn(ctx)
        val picks = Taste.shelves?.upNext.orEmpty().filter { it.key !in recent }
            // Remembered songs first: they play without spending a search.
            .sortedByDescending { it.mediaId.isNotBlank() }
            .filter { canYouTube || !it.mediaId.startsWith("youtube:") }
            .take(4)
        resolveFirst(ctx, picks, canYouTube) { track ->
            if (track != null) return@resolveFirst added(track, advance)
            // Nothing from your history fits: something new from the free charts.
            Catalogs.trending(ctx, null) { list ->
                Taste.withEngine<List<TasteEngine.Pick>>({ ranked ->
                    val t = ranked.firstOrNull()?.let { p -> list.firstOrNull { it.mediaId == p.mediaId } }
                        ?: list.firstOrNull { it.key !in recent }
                    if (t != null) added(t, advance) else { filling = false; if (advance) publish() }
                }) { engine -> engine.rankNew(list.filter { it.key !in recent }.map(::candidate), 1) }
            }
        }
    }

    private fun added(t: OnlineTrack, advance: Boolean) {
        filling = false
        queue += t
        if (advance) { index = queue.size - 1; start() } else queueChanged()
    }

    private fun resolveFirst(ctx: Context, picks: List<TasteEngine.Pick>, canYouTube: Boolean, done: (OnlineTrack?) -> Unit) {
        val p = picks.firstOrNull() ?: return done(null)
        Catalogs.resolve(ctx, p) { t ->
            if (t != null && (canYouTube || t.source != OnlineTrack.Source.YOUTUBE)) done(t)
            else resolveFirst(ctx, picks.drop(1), canYouTube, done)
        }
    }

    fun candidate(t: OnlineTrack) = TasteEngine.Candidate(t.title, t.artist, t.genre, t.mediaId, t.artUrl,
        "Trending on ${t.source.label}")

    // ---------------------------------------------------------------- YouTube player

    /** The player screen hands over its YouTube player while it's visible. */
    fun attachYouTube(e: YouTubeEmbed) {
        embed = e
        e.listener = this
        val t = current
        if (t?.source == OnlineTrack.Source.YOUTUBE && ytWanted) e.load(t.id, ytPos)
    }

    /** Leaving the screen: YouTube may not play in the background, so it pauses here. */
    fun detachYouTube(e: YouTubeEmbed) {
        if (embed !== e) return
        if (current?.source == OnlineTrack.Source.YOUTUBE) {
            ytPos = positionMs(); ytAt = SystemClock.elapsedRealtime()
            ytPlaying = false
        }
        e.pause()
        e.listener = null
        embed = null
        publish()
    }

    override fun onYouTubeReady() {}

    override fun onYouTubeState(state: Int) {
        if (current?.source != OnlineTrack.Source.YOUTUBE) return
        val was = ytPlaying
        ytPlaying = state == 1 || state == 3
        if (state == 1) failures = 0
        if (state == 2) ytWanted = false
        if (state == 1) ytWanted = true
        ytAt = SystemClock.elapsedRealtime()
        if (state == 0) return next()
        if (was != ytPlaying) publish()
    }

    override fun onYouTubeTime(positionMs: Long, durationMs: Long) {
        if (current?.source != OnlineTrack.Source.YOUTUBE) return
        val expected = positionMs()
        ytPos = positionMs; ytAt = SystemClock.elapsedRealtime()
        val durChanged = durationMs > 0 && durationMs != ytDur
        if (durationMs > 0) ytDur = durationMs
        if (durChanged || kotlin.math.abs(expected - positionMs) > 1_500) publish()
    }

    override fun onYouTubeError(code: Int) {
        val t = current ?: return
        ytPlaying = false
        failed(t, when (code) {
            2 -> "bad video id"; 5 -> "can't play here"; 100 -> "removed or private"
            101, 150 -> "the owner doesn't allow it outside YouTube"; 152, 153 -> "YouTube refused the player ($code)"
            else -> "error $code"
        })
    }

    // ---------------------------------------------------------------- ExoPlayer

    private val exoListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) failures = 0; publish() }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && current?.source != OnlineTrack.Source.YOUTUBE) next() else publish()
        }
        override fun onPlayerError(error: PlaybackException) {
            current?.let { failed(it, error.errorCodeName) }
        }
    }

    // ---------------------------------------------------------------- publishing

    private fun publish() {
        val t = current
        if (t == null) { MusicCenter.publishOwn(null); return }
        val yt = t.source == OnlineTrack.Source.YOUTUBE
        val p = exo
        val playing = if (yt) ytPlaying || (ytWanted && embed != null)
            else loading || (p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE)
        val pos = if (yt) ytPos else p?.currentPosition ?: 0
        val dur = if (yt) ytDur.takeIf { it > 0 } ?: t.durationMs else p?.duration?.takeIf { it > 0 } ?: t.durationMs
        MusicCenter.publishOwn(Track(
            title = t.title, artist = t.artist, packageName = SOURCE_PREFIX + t.source.name.lowercase(),
            art = art, accent = accent, isPlaying = playing,
            positionMs = pos, positionUpdatedAt = if (yt) ytAt else SystemClock.elapsedRealtime(), speed = 1f,
            durationMs = dur, openApp = null, mediaId = t.mediaId, artUrl = t.artUrl, genre = t.genre,
        ))
    }

    /** Square cover for the dot record; YouTube thumbnails are 4:3 with the video letterboxed in the middle. */
    private fun square(b: Bitmap, letterboxed: Boolean): Bitmap {
        val side = if (letterboxed) (minOf(b.width, b.height) * 0.75f).toInt() else minOf(b.width, b.height)
        if (side <= 0 || (b.width == side && b.height == side)) return b
        return Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }

    private fun accentOf(b: Bitmap): Int = DotArt.sample(b, 8).maxByOrNull { c ->
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        hsv[1] * 0.7f + hsv[2] * 0.3f
    }?.let(DotArt::vivid) ?: MusicListener.DEFAULT_ACCENT

    private fun toast(res: Int, vararg args: Any) {
        val c = app ?: return
        main.post { Toast.makeText(c, c.getString(res, *args), Toast.LENGTH_LONG).show() }
    }
}

package com.dotmusic.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * Dot Music is the screen; another app does the playing: YouTube Music by default, or any player you
 * pick in settings (Dot Player, Spotify, JioSaavn…). Dot follows and controls that app through
 * Android's standard media controls, and starts its picks there by asking the app's media session to
 * play from a search (the way Assistant and Android Auto do). If the app can't take that in the
 * background, the same request goes to it as an intent, which opens it.
 */
object PlayerApp {
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val DOT_PLAYER = "com.creepsmile.dotplayer"

    /** Players we know by name, shown first in the picker when installed. */
    val KNOWN = linkedMapOf(
        YT_MUSIC to "YouTube Music",
        DOT_PLAYER to "Dot Player",
        "com.spotify.music" to "Spotify",
        "com.jio.media.jiobeats" to "JioSaavn",
        "com.gaana" to "Gaana",
        "com.bsbportal.music" to "Wynk Music",
        "com.apple.android.music" to "Apple Music",
        "com.amazon.mp3" to "Amazon Music",
        "deezer.android.app" to "Deezer",
    )

    /** How a song request reached the player app. */
    enum class Route { LIBRARY, SESSION, INTENT, SEARCH_PAGE, OPENED_APP, NONE }

    /** The outcome of a play request, with what each step did (shown by the test song). */
    data class Outcome(val route: Route, val steps: List<String>, val found: String? = null)

    fun chosen(context: Context): String = DotPrefs.playerPackage(context)

    fun name(context: Context, pkg: String = chosen(context)): String =
        runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
            ?: KNOWN[pkg] ?: pkg

    fun installed(context: Context, pkg: String = chosen(context)): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun isPlayingThere(context: Context): Boolean = MusicCenter.current?.packageName == chosen(context)

    /** Play a recommendation on the chosen app. */
    fun play(context: Context, pick: TasteEngine.Pick, done: (Outcome) -> Unit = {}) =
        if (pick.kind == TasteEngine.Kind.ARTIST) play(context, pick.query, null, pick.artist, artistOnly = true, done = done)
        else play(context, pick.query, pick.title, pick.artist, artistOnly = false, done = done)

    /**
     * Without opening anything (safe from the background, e.g. a widget's Next): the app's library,
     * then its media session. [done] gets false if neither took it.
     */
    fun playQuietly(context: Context, pick: TasteEngine.Pick, done: (Boolean) -> Unit) {
        val pkg = chosen(context)
        val artistOnly = pick.kind == TasteEngine.Kind.ARTIST
        val title = pick.title.takeIf { !artistOnly }
        LibrarySearch.play(context, pkg, pick.query, title, pick.artist) { r ->
            done(r.ok || MusicCenter.playFromSearch(pkg, pick.query, extras(pick.query, title, pick.artist, artistOnly)))
        }
    }

    /**
     * Play whatever matches [query], trying in order: search the app's library and play the exact
     * result; ask its media session to play from a search; send the search as an intent (opens the
     * app); YouTube Music's search page; or just open the app. [done] runs on the main thread.
     */
    fun play(
        context: Context, query: String, title: String? = null, artist: String? = null, artistOnly: Boolean = false,
        done: (Outcome) -> Unit = {},
    ) {
        val q = query.trim()
        if (q.isEmpty()) return done(Outcome(Route.NONE, emptyList()))
        val pkg = chosen(context)
        val steps = ArrayList<String>()
        LibrarySearch.play(context, pkg, q, title.takeIf { !artistOnly }, artist) { r ->
            steps += context.getString(R.string.step_library, r.detail)
            done(if (r.ok) Outcome(Route.LIBRARY, steps, r.found) else fallback(context, pkg, q, title, artist, artistOnly, steps))
        }
    }

    private fun fallback(
        context: Context, pkg: String, q: String, title: String?, artist: String?, artistOnly: Boolean, steps: MutableList<String>,
    ): Outcome {
        val extras = extras(q, title, artist, artistOnly)
        // Its media session: no app switch, but some apps advertise this and ignore it.
        if (MusicCenter.playFromSearch(pkg, q, extras)) {
            steps += context.getString(R.string.step_session_sent)
            return Outcome(Route.SESSION, steps)
        }
        steps += context.getString(R.string.step_session_no)
        if (start(context, searchIntent(pkg, extras))) {
            steps += context.getString(R.string.step_intent_sent)
            return Outcome(Route.INTENT, steps)
        }
        steps += context.getString(R.string.step_intent_no)
        if (pkg == YT_MUSIC) {
            val page = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q)))
                .setPackage(YT_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, page)) return Outcome(Route.SEARCH_PAGE, steps)
        }
        return if (open(context, pkg)) Outcome(Route.OPENED_APP, steps + context.getString(R.string.step_opened))
        else Outcome(Route.NONE, steps)
    }

    fun open(context: Context, pkg: String = chosen(context)): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { start(context, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } == true

    fun getIt(context: Context, pkg: String = chosen(context)): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ||
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Installed apps that look like music players: known ones, ones that take play-from-search, ones seen playing. */
    fun candidates(context: Context): List<String> {
        val pm = context.packageManager
        val out = LinkedHashSet<String>()
        KNOWN.keys.filterTo(out) { installed(context, it) }
        runCatching {
            pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
                .mapTo(out) { it.activityInfo.packageName }
        }
        for (action in listOf("android.media.browse.MediaBrowserService", "androidx.media3.session.MediaSessionService",
            "androidx.media3.session.MediaLibraryService")) {
            runCatching { pm.queryIntentServices(Intent(action), 0).mapTo(out) { it.serviceInfo.packageName } }
        }
        out += MusicCenter.sessionPackages()
        out -= context.packageName
        out += chosen(context)
        return out.toList()
    }

    /** One line of the "what does this app support" check. Null: can't tell until it plays something. */
    data class Check(val label: String, val ok: Boolean?)

    fun checks(context: Context, pkg: String = chosen(context)): List<Check> {
        val s = MusicCenter.sessionInfo(pkg)
        val a = s?.actions
        fun has(flag: Long) = a?.let { (it and flag) != 0L }
        val intentOk = runCatching {
            context.packageManager.queryIntentActivities(searchIntent(pkg, Bundle()), 0).isNotEmpty()
        }.getOrDefault(false)
        val c = context
        return listOf(
            Check(c.getString(R.string.check_installed), installed(context, pkg)),
            Check(c.getString(R.string.check_access), MusicListener.enabled(context)),
            Check(c.getString(R.string.check_seen), s != null),
            Check(c.getString(R.string.check_song_info), s?.let { it.hasTitle }),
            Check(c.getString(R.string.check_cover), s?.let { it.hasArt }),
            Check(c.getString(R.string.check_play_pause), has(PlaybackState.ACTION_PLAY_PAUSE)?.let { it || has(PlaybackState.ACTION_PLAY) == true }),
            Check(c.getString(R.string.check_skip), has(PlaybackState.ACTION_SKIP_TO_NEXT)),
            Check(c.getString(R.string.check_seek), has(PlaybackState.ACTION_SEEK_TO)),
            Check(c.getString(R.string.check_library), LibrarySearch.service(context, pkg) != null),
            Check(c.getString(R.string.check_search_session), has(PlaybackState.ACTION_PLAY_FROM_SEARCH)),
            Check(c.getString(R.string.check_search_intent), intentOk),
            Check(c.getString(R.string.check_queue), s?.let { it.hasQueue }),
        )
    }

    private fun searchIntent(pkg: String, extras: Bundle) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        .setPackage(pkg).putExtras(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun extras(q: String, title: String?, artist: String?, artistOnly: Boolean) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS,
            if (artistOnly) MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE else MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        title?.takeIf { it.isNotBlank() }?.let { putString(MediaStore.EXTRA_MEDIA_TITLE, it) }
        artist?.takeIf { it.isNotBlank() }?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        putString(SearchManager.QUERY, q)
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)
}

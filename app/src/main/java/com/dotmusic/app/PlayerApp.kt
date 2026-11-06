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
    enum class Route { SESSION, INTENT, SEARCH_PAGE, OPENED_APP, NONE }

    fun chosen(context: Context): String = DotPrefs.playerPackage(context)

    fun name(context: Context, pkg: String = chosen(context)): String =
        runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
            ?: KNOWN[pkg] ?: pkg

    fun installed(context: Context, pkg: String = chosen(context)): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun isPlayingThere(context: Context): Boolean = MusicCenter.current?.packageName == chosen(context)

    /** Play a recommendation on the chosen app. */
    fun play(context: Context, pick: TasteEngine.Pick): Route =
        if (pick.kind == TasteEngine.Kind.ARTIST) play(context, pick.query, null, pick.artist, artistOnly = true)
        else play(context, pick.query, pick.title, pick.artist, artistOnly = false)

    /** Only through the running session (safe from the background, e.g. a widget's Next). */
    fun playInSession(context: Context, pick: TasteEngine.Pick): Boolean {
        val artistOnly = pick.kind == TasteEngine.Kind.ARTIST
        return MusicCenter.playFromSearch(chosen(context), pick.query,
            extras(pick.query, pick.title.takeIf { !artistOnly }, pick.artist, artistOnly))
    }

    /** Play whatever matches [query]; most apps play the best result and keep similar songs going after it. */
    fun play(context: Context, query: String, title: String? = null, artist: String? = null, artistOnly: Boolean = false): Route {
        val q = query.trim()
        if (q.isEmpty()) return Route.NONE
        val pkg = chosen(context)
        val extras = extras(q, title, artist, artistOnly)
        // 1. Straight to its media session: no app switch.
        if (MusicCenter.playFromSearch(pkg, q, extras)) return Route.SESSION
        // 2. The same request as an intent: the app opens and plays.
        if (start(context, searchIntent(pkg, extras))) return Route.INTENT
        // 3. YouTube Music's own search page.
        if (pkg == YT_MUSIC) {
            val page = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q)))
                .setPackage(YT_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, page)) return Route.SEARCH_PAGE
        }
        // 4. At least open it, so you can search there.
        return if (open(context, pkg)) Route.OPENED_APP else Route.NONE
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

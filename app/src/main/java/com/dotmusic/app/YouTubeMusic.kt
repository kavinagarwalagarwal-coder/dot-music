package com.dotmusic.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * Dot Music is the screen; YouTube Music does the playing, with your account, your library and
 * background play. Songs are started by asking YouTube Music's media session to play from a search
 * (the way Assistant and Android Auto do it), which keeps you in Dot. If YouTube Music isn't running
 * yet, the same request goes to it as an intent, and it opens and starts playing.
 */
object YouTubeMusic {
    const val PACKAGE = "com.google.android.apps.youtube.music"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    fun isPlayingThere(): Boolean = MusicCenter.current?.packageName == PACKAGE

    /** Play a recommendation. Returns false only when there was no way to reach YouTube Music. */
    fun play(context: Context, pick: TasteEngine.Pick): Boolean =
        if (pick.kind == TasteEngine.Kind.ARTIST) play(context, pick.query, null, pick.artist, artistOnly = true)
        else play(context, pick.query, pick.title, pick.artist, artistOnly = false)

    /** Only through the running session (safe from the background, e.g. a widget's Next). */
    fun playInSession(pick: TasteEngine.Pick): Boolean {
        val artistOnly = pick.kind == TasteEngine.Kind.ARTIST
        return MusicCenter.playFromSearch(pick.query, extras(pick.query, pick.title.takeIf { !artistOnly }, pick.artist, artistOnly))
    }

    private fun extras(q: String, title: String?, artist: String?, artistOnly: Boolean) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS,
            if (artistOnly) MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE else MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        title?.takeIf { it.isNotBlank() }?.let { putString(MediaStore.EXTRA_MEDIA_TITLE, it) }
        artist?.takeIf { it.isNotBlank() }?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        putString(SearchManager.QUERY, q)
    }

    /** Play whatever matches [query]; YouTube Music picks the best result and keeps a radio going after it. */
    fun play(context: Context, query: String, title: String? = null, artist: String? = null, artistOnly: Boolean = false): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val extras = extras(q, title, artist, artistOnly)
        // 1. Straight to its media session: no app switch.
        if (MusicCenter.playFromSearch(q, extras)) return true
        // 2. The same request as an intent: YouTube Music opens and plays.
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(PACKAGE).putExtras(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, search)) return true
        // 3. Its search page.
        val page = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, Intent(page).setPackage(PACKAGE))) return true
        return start(context, page)
    }

    fun open(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { start(context, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ?: getIt(context)

    fun getIt(context: Context): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ||
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)
}

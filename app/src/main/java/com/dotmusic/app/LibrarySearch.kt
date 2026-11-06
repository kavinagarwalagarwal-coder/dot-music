package com.dotmusic.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat

/**
 * Starts a song in another app through its music library, the way Android Auto does: connect to the
 * app's media browser service, search its library, then play the exact item it returns by its id.
 * Apps that ignore a plain "play this search" request (many Media3 players advertise it without
 * handling it) usually support this, because Android Auto needs it.
 */
object LibrarySearch {
    data class Result(val ok: Boolean, val detail: String, val found: String? = null)

    private val main = Handler(Looper.getMainLooper())

    /** The app's media browser service, if it has one. */
    fun service(context: Context, pkg: String): ComponentName? = runCatching {
        context.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /** Search [pkg]'s library for [query] and play the best match. [done] runs once, on the main thread. */
    fun play(context: Context, pkg: String, query: String, title: String?, artist: String?, done: (Result) -> Unit) {
        val ctx = context.applicationContext
        val component = service(ctx, pkg) ?: return done(Result(false, ctx.getString(R.string.lib_no_service)))
        var browser: MediaBrowserCompat? = null
        var finished = false
        lateinit var timeout: Runnable
        fun finish(r: Result, linger: Long = 0) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeout)
            // Stay connected a little after a play request, so the app's service isn't unbound before it starts.
            main.postDelayed({ runCatching { browser?.disconnect() } }, linger)
            done(r)
        }
        timeout = Runnable { finish(Result(false, ctx.getString(R.string.lib_timeout))) }

        browser = MediaBrowserCompat(ctx, component, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                runCatching {
                    b.search(query, Bundle(), object : MediaBrowserCompat.SearchCallback() {
                        override fun onSearchResult(q: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) {
                            val best = best(items.filter { it.isPlayable && it.mediaId != null }, title, artist)
                                ?: return finish(Result(false, ctx.getString(R.string.lib_no_results, items.size)))
                            val name = listOfNotNull(best.description.title, best.description.subtitle).joinToString(" — ")
                            val ok = runCatching {
                                MediaControllerCompat(ctx, b.sessionToken).transportControls.playFromMediaId(best.mediaId, Bundle())
                            }.isSuccess
                            finish(if (ok) Result(true, ctx.getString(R.string.lib_started), name)
                                else Result(false, ctx.getString(R.string.lib_play_failed), name), linger = 8_000)
                        }

                        override fun onError(q: String, extras: Bundle?) =
                            finish(Result(false, ctx.getString(R.string.lib_search_unsupported)))
                    })
                }.onFailure { finish(Result(false, ctx.getString(R.string.lib_search_unsupported))) }
            }

            override fun onConnectionFailed() = finish(Result(false, ctx.getString(R.string.lib_refused)))
            override fun onConnectionSuspended() = finish(Result(false, ctx.getString(R.string.lib_refused)))
        }, null)
        main.postDelayed(timeout, TIMEOUT_MS)
        runCatching { browser?.connect() }.onFailure { finish(Result(false, ctx.getString(R.string.lib_refused))) }
    }

    /** The result that is the same song (title and artist), else the first one. */
    private fun best(items: List<MediaBrowserCompat.MediaItem>, title: String?, artist: String?): MediaBrowserCompat.MediaItem? {
        if (items.isEmpty()) return null
        if (title.isNullOrBlank()) return items.first()
        val want = SongKeys.key(title, artist.orEmpty())
        val wantTitle = SongKeys.norm(SongKeys.displayTitle(title, artist.orEmpty()))
        return items.firstOrNull { SongKeys.key(it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty()) == want }
            ?: items.firstOrNull { SongKeys.norm(SongKeys.displayTitle(it.description.title?.toString().orEmpty())) == wantTitle }
            ?: items.first()
    }

    private const val TIMEOUT_MS = 10_000L
}

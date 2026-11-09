package com.dotmusic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small blocking HTTP helpers; always called off the main thread. */
object Net {
    private const val AGENT = "DotMusic/1.0 (Android)"

    fun get(url: String, timeoutMs: Int = 12_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: ${body.take(300)}")
            return body
        } finally { c.disconnect() }
    }

    fun bitmap(url: String, maxSize: Int): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", AGENT)
            val bytes = c.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally { c.disconnect() }
    }.getOrNull()
}

/** Cover art and thumbnails, cached in memory. */
object ArtLoader {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String, size: Int, done: (Bitmap?) -> Unit) {
        if (url.isBlank()) return done(null)
        val key = "$size|$url"
        cache.get(key)?.let { return done(it) }
        pool.execute {
            val b = Net.bitmap(url, size)
            if (b != null) cache.put(key, b)
            main.post { done(b) }
        }
    }
}

/**
 * Dot's online sources. YouTube (official Data API search; plays in YouTube's embedded player)
 * needs your own API key; Audius needs nothing; Jamendo needs a free client id.
 */
object Catalogs {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    /** Per-source requests of one search; separate so a search never waits on its own pool. */
    private val io = Executors.newCachedThreadPool()
    private const val APP = "DotMusic"
    private const val AUDIUS = "https://api.audius.co/v1"
    private const val JAMENDO = "https://api.jamendo.com/v3.0"
    private val searchCache = object : LruCache<String, List<OnlineTrack>>(40) {}

    data class Results(val tracks: List<OnlineTrack>, val errors: List<String>)

    fun youtubeOn(c: Context) = DotPrefs.youtubeKey(c).isNotBlank()
    fun audiusOn(c: Context) = DotPrefs.audius(c)
    fun jamendoOn(c: Context) = DotPrefs.jamendoId(c).isNotBlank()
    fun anyOn(c: Context) = youtubeOn(c) || audiusOn(c) || jamendoOn(c)

    /** Search every source that's on, in parallel; results interleaved, YouTube first. */
    fun search(context: Context, query: String, done: (Results) -> Unit) {
        val c = context.applicationContext
        val q = query.trim()
        if (q.isEmpty()) return done(Results(emptyList(), emptyList()))
        searchCache.get(q.lowercase())?.let { return done(Results(it, emptyList())) }
        pool.execute {
            val errors = ArrayList<String>()
            fun <T> attempt(name: String, on: Boolean, f: () -> List<T>): List<T> =
                if (!on) emptyList() else runCatching(f).getOrElse { errors += "$name: ${it.message ?: it.javaClass.simpleName}"; emptyList() }
            val yt = io.submit<List<OnlineTrack>> { attempt("YouTube", youtubeOn(c)) { youtubeSearch(c, q) } }
            val au = io.submit<List<OnlineTrack>> { attempt("Audius", audiusOn(c)) { audiusSearch(q) } }
            val ja = io.submit<List<OnlineTrack>> { attempt("Jamendo", jamendoOn(c)) { jamendoSearch(c, q) } }
            val merged = CatalogParse.merge(yt.get(), au.get(), ja.get())
            if (merged.isNotEmpty()) searchCache.put(q.lowercase(), merged)
            main.post { done(Results(merged, errors)) }
        }
    }

    /** Free sources' charts, for Discover: Audius trending, Jamendo's popular this week. */
    fun trending(context: Context, genre: String?, done: (List<OnlineTrack>) -> Unit) {
        val c = context.applicationContext
        pool.execute {
            val au = if (audiusOn(c)) runCatching {
                CatalogParse.audius(Net.get("$AUDIUS/tracks/trending?time=week&app_name=$APP" +
                    (genre?.takeIf { it.isNotBlank() }?.let { "&genre=" + Uri.encode(it) } ?: "")))
            }.getOrDefault(emptyList()) else emptyList()
            val ja = if (jamendoOn(c)) runCatching {
                CatalogParse.jamendo(Net.get("$JAMENDO/tracks/?client_id=${Uri.encode(DotPrefs.jamendoId(c))}&format=json&limit=30" +
                    "&order=popularity_week&audioformat=mp32&include=musicinfo&imagesize=300" +
                    (genre?.takeIf { it.isNotBlank() }?.let { "&tags=" + Uri.encode(it.lowercase()) } ?: "")))
            }.getOrDefault(emptyList()) else emptyList()
            val all = CatalogParse.merge(au, ja)
            main.post { done(all) }
        }
    }

    /** Find a playable track for a recommendation: by its remembered id, else by searching. */
    fun resolve(context: Context, pick: TasteEngine.Pick, done: (OnlineTrack?) -> Unit) {
        val c = context.applicationContext
        if (pick.mediaId.isNotBlank()) {
            OnlineTrack.fromMediaId(pick.mediaId, pick.title, pick.artist, pick.artUrl)?.let { t ->
                val on = when (t.source) {
                    OnlineTrack.Source.YOUTUBE -> youtubeOn(c)
                    OnlineTrack.Source.AUDIUS -> audiusOn(c)
                    OnlineTrack.Source.JAMENDO -> jamendoOn(c)
                }
                if (on) return done(t)
            }
        }
        val query = if (pick.kind == TasteEngine.Kind.ARTIST) pick.artist else "${pick.title} ${pick.artist}"
        search(c, query) { r ->
            done(if (pick.kind == TasteEngine.Kind.ARTIST) r.tracks.firstOrNull()
                else CatalogParse.best(r.tracks, pick.title, pick.artist))
        }
    }

    /** The audio URL for ExoPlayer (not used for YouTube, which plays in its own player). Blocking. */
    fun streamUrl(context: Context, t: OnlineTrack): String? = when (t.source) {
        OnlineTrack.Source.YOUTUBE -> null
        OnlineTrack.Source.AUDIUS -> "$AUDIUS/tracks/${Uri.encode(t.id)}/stream?app_name=$APP"
        OnlineTrack.Source.JAMENDO -> t.streamUrl.ifBlank {
            runCatching {
                CatalogParse.jamendo(Net.get("$JAMENDO/tracks/?client_id=${Uri.encode(DotPrefs.jamendoId(context))}" +
                    "&format=json&audioformat=mp32&id=${Uri.encode(t.id)}")).firstOrNull()?.streamUrl
            }.getOrNull()
        }
    }

    private fun youtubeSearch(c: Context, q: String): List<OnlineTrack> = CatalogParse.youtube(Net.get(
        "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoCategoryId=10&videoEmbeddable=true" +
            "&videoSyndicated=true&maxResults=15&q=${Uri.encode(q)}&key=${Uri.encode(DotPrefs.youtubeKey(c))}"))

    private fun audiusSearch(q: String) = CatalogParse.audius(Net.get("$AUDIUS/tracks/search?query=${Uri.encode(q)}&app_name=$APP"))

    private fun jamendoSearch(c: Context, q: String) = CatalogParse.jamendo(Net.get(
        "$JAMENDO/tracks/?client_id=${Uri.encode(DotPrefs.jamendoId(c))}&format=json&limit=15&search=${Uri.encode(q)}" +
            "&audioformat=mp32&include=musicinfo&imagesize=300"))
}

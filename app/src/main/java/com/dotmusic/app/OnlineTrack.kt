package com.dotmusic.app

import org.json.JSONObject

/** A song from one of Dot's online sources, ready to play in Dot's own player. */
data class OnlineTrack(
    val source: Source,
    /** The source's own id: a YouTube video id, an Audius track id, a Jamendo track id. */
    val id: String,
    val title: String,
    val artist: String,
    val artUrl: String,
    val durationMs: Long = 0,
    val genre: String = "",
    /** Direct audio URL when the source gives one up front (Jamendo). */
    val streamUrl: String = "",
) {
    enum class Source(val label: String) { YOUTUBE("YouTube"), AUDIUS("Audius"), JAMENDO("Jamendo") }

    /** Stable id across the app and the listening history: "youtube:dQw4w9WgXcQ". */
    val mediaId: String get() = "${source.name.lowercase()}:$id"

    val key: String get() = SongKeys.key(title, artist)

    companion object {
        fun sourceOf(mediaId: String): Source? =
            Source.entries.firstOrNull { mediaId.startsWith(it.name.lowercase() + ":") }

        fun fromMediaId(mediaId: String, title: String, artist: String, artUrl: String = "", durationMs: Long = 0, genre: String = ""): OnlineTrack? {
            val source = sourceOf(mediaId) ?: return null
            val id = mediaId.substringAfter(':').takeIf { it.isNotBlank() } ?: return null
            return OnlineTrack(source, id, title, artist, artUrl, durationMs, genre)
        }
    }
}

/** Turns each source's JSON into [OnlineTrack]s. No Android here, so it's unit tested. */
object CatalogParse {
    /** YouTube Data API v3 search.list. Video titles are usually "Artist - Song (Official Video)". */
    fun youtube(json: String): List<OnlineTrack> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<OnlineTrack>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val videoId = item.optJSONObject("id")?.optString("videoId").orEmpty()
            val sn = item.optJSONObject("snippet") ?: continue
            if (videoId.isBlank() || !ID.matches(videoId)) continue
            if (sn.optString("liveBroadcastContent", "none") != "none") continue
            val rawTitle = entities(sn.optString("title"))
            val channel = entities(sn.optString("channelTitle"))
            val thumbs = sn.optJSONObject("thumbnails")
            val art = listOf("high", "medium", "default").firstNotNullOfOrNull {
                thumbs?.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotBlank() }
            }.orEmpty()
            out += OnlineTrack(OnlineTrack.Source.YOUTUBE, videoId, SongKeys.displayTitle(rawTitle, channel),
                SongKeys.cleanArtist(channel, rawTitle), art)
        }
        return out
    }

    /** Audius /v1/tracks/search and /v1/tracks/trending. */
    fun audius(json: String): List<OnlineTrack> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OnlineTrack>()
        for (i in 0 until data.length()) {
            val t = data.optJSONObject(i) ?: continue
            val id = t.optString("id")
            if (id.isBlank() || t.optBoolean("is_streamable", true).not()) continue
            val art = t.optJSONObject("artwork")?.let { a -> a.optString("480x480").ifBlank { a.optString("150x150") } }.orEmpty()
            out += OnlineTrack(OnlineTrack.Source.AUDIUS, id, t.optString("title"),
                t.optJSONObject("user")?.optString("name").orEmpty(), art,
                t.optLong("duration") * 1000, t.optString("genre"))
        }
        return out
    }

    /** Jamendo /v3.0/tracks. */
    fun jamendo(json: String): List<OnlineTrack> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<OnlineTrack>()
        for (i in 0 until results.length()) {
            val t = results.optJSONObject(i) ?: continue
            val id = t.optString("id")
            val audio = t.optString("audio")
            if (id.isBlank() || audio.isBlank()) continue
            val genre = t.optJSONObject("musicinfo")?.optJSONObject("tags")?.optJSONArray("genres")?.optString(0).orEmpty()
            out += OnlineTrack(OnlineTrack.Source.JAMENDO, id, t.optString("name"), t.optString("artist_name"),
                t.optString("image"), t.optLong("duration") * 1000, genre, audio)
        }
        return out
    }

    /** Merge results from several sources: round-robin, one copy of each song. */
    fun merge(vararg lists: List<OnlineTrack>): List<OnlineTrack> {
        val seen = HashSet<String>()
        val out = ArrayList<OnlineTrack>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) {
            val t = l.getOrNull(i) ?: continue
            if (seen.add(t.key)) out += t
        }
        return out
    }

    /** The result that is the same song, else the first. */
    fun best(results: List<OnlineTrack>, title: String, artist: String): OnlineTrack? {
        val want = SongKeys.key(title, artist)
        return results.firstOrNull { it.key == want } ?: results.firstOrNull()
    }

    private val ID = Regex("[A-Za-z0-9_-]{6,20}")

    /** YouTube's API HTML-escapes titles. */
    fun entities(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x") || e.startsWith("#X")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    code?.let { String(Character.toChars(it)) } ?: m.value
                }
            }
        }
    }
}

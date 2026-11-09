package com.dotmusic.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Dot's recommendation engine. It learns only from what you listen to, on the phone.
 *
 * Signals, from each [Listen]:
 *  - **How it ended.** Played through is a like, left half way is a shrug, skipped early is a dislike.
 *  - **Recency.** Every signal fades with a half-life, so taste can move on.
 *  - **Flow.** Which song you happily played after which (a song-to-song graph, plus an
 *    artist-to-artist one), walked two steps out from what's playing now.
 *  - **Time of day.** Songs you lean on in the morning versus late at night.
 *  - **Fatigue.** Songs heard in the last hours rest for a while.
 *
 * Ranking blends those with a **Thompson sample** of how much you like each song (a Beta over its
 * likes and dislikes), so songs it is unsure about still get a turn instead of the same ten forever.
 * Then a **diversity pass** spreads artists out. Picking a song starts it on YouTube Music, whose own
 * radio keeps going with music you haven't heard yet; whatever you keep or skip there feeds back in.
 */
class TasteEngine(
    listens: List<Listen>,
    private val now: Long,
    private val random: Random = Random.Default,
) {
    enum class Kind { SONG, ARTIST }

    data class Pick(
        val title: String,
        val artist: String,
        val key: String,
        val reason: String,
        val kind: Kind = Kind.SONG,
        val score: Double = 0.0,
        /** Where Dot last played it ("youtube:…", "audius:…"), so it plays again without a search. */
        val mediaId: String = "",
        val artUrl: String = "",
    ) {
        /** What to ask YouTube Music for. */
        val query: String get() = if (kind == Kind.ARTIST) artist else "$title $artist".trim()
    }

    data class Shelves(
        val upNext: List<Pick>,
        val forNow: List<Pick>,
        val onRepeat: List<Pick>,
        val rediscover: List<Pick>,
        val explore: List<Pick>,
        val slot: Int,
        val listens: Int,
    )

    class Song(val key: String, val artistKey: String, var title: String, var artist: String) {
        var plays = 0
        /** Lifetime likes and dislikes (Beta evidence). */
        var good = 0.0
        var bad = 0.0
        /** Decayed affinity: long memory, and this week. */
        var score = 0.0
        var recent = 0.0
        var weekPlays = 0
        var lastPlayed = 0L
        val slots = DoubleArray(SLOTS)
        /** Recently heard: needs a rest. */
        var fatigue = 0.0
        var mediaId = ""
        var artUrl = ""
        var genre = ""

        fun pick(reason: String, kind: Kind = Kind.SONG, score: Double = 0.0) =
            Pick(title, artist, key, reason, kind, score, mediaId, artUrl)
    }

    class Artist(val key: String, var name: String) {
        var score = 0.0
        var plays = 0
        var lastPlayed = 0L
    }

    val songs = HashMap<String, Song>()
    val artists = HashMap<String, Artist>()
    private val next = HashMap<String, HashMap<String, Double>>()
    private val artistNext = HashMap<String, HashMap<String, Double>>()
    /** Decayed liking per genre (lowercase), from sources that tag genres. */
    val genres = HashMap<String, Double>()
    private val slotTotals = DoubleArray(SLOTS)
    private var slotSum = 0.0
    var listenCount = 0
        private set

    val isEmpty: Boolean get() = songs.isEmpty()

    init { build(listens) }

    // ---------------------------------------------------------------- learning

    private fun build(all: List<Listen>) {
        var prev: Listen? = null
        var prevKey: String? = null
        var prevArtist: String? = null
        var prevWeight = 0.0
        for (l in all.sortedBy { it.startedAt }) {
            if (l.durationMs > MAX_TRACK_MS || l.title.isBlank()) continue
            val key = SongKeys.key(l.title, l.artist)
            val aKey = SongKeys.artistKey(l.artist, l.title)
            listenCount++
            val song = songs.getOrPut(key) { Song(key, aKey, "", "") }
            song.title = SongKeys.displayTitle(l.title, l.artist)
            song.artist = SongKeys.cleanArtist(l.artist, l.title)
            val artist = artists.getOrPut(aKey) { Artist(aKey, "") }
            artist.name = SongKeys.displayArtist(l.artist, l.title)

            if (l.mediaId.isNotBlank()) { song.mediaId = l.mediaId; if (l.artUrl.isNotBlank()) song.artUrl = l.artUrl }
            if (l.genre.isNotBlank()) song.genre = l.genre
            val w = outcome(l)
            val age = (now - l.startedAt).coerceAtLeast(0)
            val decay = half(age, HALF_LIFE_MS)
            if (l.genre.isNotBlank()) genres.merge(l.genre.lowercase(), w * decay, Double::plus)
            song.plays++
            song.score += w * decay
            song.recent += w * half(age, WEEK_MS)
            if (age < WEEK_MS) song.weekPlays++
            if (w > 0) song.good += w else song.bad -= w
            song.lastPlayed = max(song.lastPlayed, l.startedAt)
            val hours = age / HOUR_MS.toDouble()
            if (hours < 24) song.fatigue += 0.5.pow(hours / 3.0)
            if (w > 0) {
                val s = slotOf(l.hour)
                song.slots[s] += w; slotTotals[s] += w; slotSum += w
            }
            artist.score += w * decay
            artist.plays++
            artist.lastPlayed = max(artist.lastPlayed, l.startedAt)

            // Flow: B was enjoyed right after A.
            val p = prev
            if (p != null && prevKey != null && prevKey != key && w > 0 && prevWeight > 0 &&
                l.startedAt - (p.startedAt + p.listenedMs) < SESSION_GAP_MS) {
                next.getOrPut(prevKey) { HashMap() }.merge(key, w * decay, Double::plus)
                val pa = prevArtist!!
                artistNext.getOrPut(pa) { HashMap() }.merge(aKey, w * decay * if (pa == aKey) 0.4 else 1.0, Double::plus)
            }
            prev = l; prevKey = key; prevArtist = aKey; prevWeight = w
        }
    }

    // ---------------------------------------------------------------- shelves

    fun shelves(currentTitle: String?, currentArtist: String?, hour: Int, limit: Int = 8): Shelves {
        val slot = slotOf(hour)
        val up = upNext(currentTitle, currentArtist, hour, limit)
        val taken = up.take(3).mapTo(HashSet()) { it.key }
        return Shelves(
            upNext = up,
            forNow = forNow(hour, limit).filter { it.key !in taken },
            onRepeat = onRepeat(limit),
            rediscover = rediscover(limit),
            explore = explore(limit),
            slot = slot,
            listens = listenCount,
        )
    }

    /** What to play after the current song. */
    fun upNext(currentTitle: String?, currentArtist: String?, hour: Int, limit: Int = 8): List<Pick> {
        val cur = currentTitle?.let { SongKeys.key(it, currentArtist.orEmpty()) }
        val curArtist = currentTitle?.let { SongKeys.artistKey(currentArtist.orEmpty(), it) }
        val curTitle = currentTitle?.let { SongKeys.displayTitle(it, currentArtist.orEmpty()) }
        val flow = flowFrom(cur, curArtist)
        val slot = slotOf(hour)
        val maxAff = songs.values.maxOfOrNull { ln1p(max(0.0, it.score)) }?.takeIf { it > 0 } ?: 1.0
        val maxArtist = artists.values.maxOfOrNull { max(0.0, it.score) }?.takeIf { it > 0 } ?: 1.0

        val scored = songs.values.asSequence()
            .filter { it.key != cur && !banned(it) }
            .map { s ->
                val like = sampleBeta(1 + s.good, 1 + s.bad)
                val aff = ln1p(max(0.0, s.score)) / maxAff
                val art = max(0.0, artists[s.artistKey]?.score ?: 0.0) / maxArtist
                val f = flow[s.key] ?: 0.0
                val ctx = context(s, slot)
                val rest = min(1.0, s.fatigue) + if (now - s.lastPlayed < 45 * MINUTE_MS) 1.0 else 0.0
                val score = 0.25 * like + 0.25 * aff + 0.15 * art + 0.40 * f + 0.10 * ctx - 0.5 * rest
                val reason = when {
                    f >= 0.25 && curTitle != null -> "Often follows $curTitle"
                    ctx >= 0.4 -> "A ${SLOT_NAMES[slot]} favourite"
                    s.good >= 3 && now - s.lastPlayed > 21 * DAY_MS -> "Not heard in ${ago(now - s.lastPlayed)}"
                    art >= 0.6 -> "Because you play ${artists[s.artistKey]?.name ?: s.artist}"
                    s.good + s.bad < 2 -> "Worth another listen"
                    else -> "In your rotation"
                }
                s.pick(reason, Kind.SONG, score)
            }.toList()
        return diversify(scored, limit, curArtist)
    }

    /** Songs you lean on at this time of day. */
    fun forNow(hour: Int, limit: Int = 8): List<Pick> {
        val slot = slotOf(hour)
        val maxAff = songs.values.maxOfOrNull { ln1p(max(0.0, it.score)) }?.takeIf { it > 0 } ?: 1.0
        val scored = songs.values.asSequence()
            .filter { !banned(it) && it.good >= 1 && now - it.lastPlayed > 2 * HOUR_MS }
            .map { s -> s to context(s, slot) }
            .filter { it.second > 0.15 }
            .map { (s, ctx) ->
                s.pick("You play this most in the ${SLOT_NAMES[slot]}",
                    score = 0.6 * ctx + 0.4 * ln1p(max(0.0, s.score)) / maxAff + 0.15 * sampleBeta(1 + s.good, 1 + s.bad))
            }.toList()
        return diversify(scored, limit, null)
    }

    /** What you keep coming back to this week. */
    fun onRepeat(limit: Int = 8): List<Pick> {
        val scored = songs.values.filter { it.recent > 0.5 && !banned(it) }.map { s ->
            s.pick(if (s.weekPlays >= 3) "Played ${s.weekPlays} times this week" else "Lately",
                score = s.recent)
        }
        return diversify(scored, limit, null, artistPenalty = 0.1)
    }

    /** Old favourites you've drifted away from. */
    fun rediscover(limit: Int = 8): List<Pick> {
        val scored = songs.values.filter { it.good >= 2.5 && now - it.lastPlayed > 21 * DAY_MS && !banned(it) }.map { s ->
            val ratio = s.good / (s.good + s.bad)
            s.pick("Last played ${ago(now - s.lastPlayed)} ago",
                score = s.good * ratio * (0.8 + 0.4 * random.nextDouble()))
        }
        return diversify(scored, limit, null)
    }

    /**
     * Ways out of the usual: artists that sit next to your favourites in your listening, and
     * radios seeded from songs you love, where YouTube Music brings in music you haven't heard.
     */
    fun explore(limit: Int = 8): List<Pick> {
        val top = artists.values.filter { it.score > 0 }.sortedByDescending { it.score }.take(6)
        val pull = HashMap<String, Double>()
        val via = HashMap<String, Pair<Double, String>>()
        for (a in top) {
            val out = artistNext[a.key] ?: continue
            val sum = out.values.sum().takeIf { it > 0 } ?: continue
            for ((b, w) in out) {
                // Only artists you play less than the one they follow: that's the exploring part.
                if (b == a.key || (artists[b]?.score ?: 0.0) >= a.score) continue
                val share = w / sum * a.score
                pull.merge(b, share, Double::plus)
                if ((via[b]?.first ?: -1.0) < share) via[b] = share to a.name
            }
        }
        val artistPicks = pull.mapNotNull { (k, v) ->
            val a = artists[k] ?: return@mapNotNull null
            if (a.score < -0.5) return@mapNotNull null
            Pick("", a.name, "artist:$k", "Pairs well with ${via[k]?.second ?: "your favourites"}", Kind.ARTIST,
                v / (1 + a.plays / 10.0))
        }.sortedByDescending { it.score }.take(limit / 2)

        val seeds = songs.values.filter { it.good >= 1.5 && !banned(it) }.map { s ->
            s.pick("A favourite to start a run from", Kind.SONG,
                sampleBeta(1 + s.good, 1 + s.bad) + 0.3 * random.nextDouble())
        }
        val radios = diversify(seeds, limit - artistPicks.size, null, artistPenalty = 0.5)

        val fallback = if (artistPicks.isEmpty()) top.take(3).map {
            Pick("", it.name, "artist:${it.key}", "Shuffle ${it.name}", Kind.ARTIST, it.score)
        } else emptyList()
        return (artistPicks + fallback + radios).take(limit)
    }

    /** A song from outside your history (a chart, a search) that Discover might suggest. */
    data class Candidate(
        val title: String, val artist: String, val genre: String, val mediaId: String, val artUrl: String,
        /** Why it's here if nothing personal applies, e.g. "Trending on Audius". */
        val origin: String,
    )

    /** Your favourite genre lately, if your sources tag genres. */
    fun topGenre(): String? = genres.filterValues { it > 0 }.maxByOrNull { it.value }?.key

    /**
     * Rank songs you've never played: artists you already like count most, then genres you like,
     * with a little randomness so Discover changes. Songs you've heard are left out.
     */
    fun rankNew(candidates: List<Candidate>, limit: Int = 8): List<Pick> {
        val maxArtist = artists.values.maxOfOrNull { max(0.0, it.score) }?.takeIf { it > 0 } ?: 1.0
        val maxGenre = genres.values.maxOfOrNull { max(0.0, it) }?.takeIf { it > 0 } ?: 1.0
        val scored = candidates.asSequence()
            .filter { it.title.isNotBlank() && SongKeys.key(it.title, it.artist) !in songs }
            .distinctBy { SongKeys.key(it.title, it.artist) }
            .map { c ->
                val artist = artists[SongKeys.artistKey(c.artist, c.title)]
                val art = max(0.0, artist?.score ?: 0.0) / maxArtist
                val gen = max(0.0, genres[c.genre.lowercase()] ?: 0.0) / maxGenre
                if ((artist?.score ?: 0.0) < -0.5) return@map null
                val reason = when {
                    art > 0.05 -> "New to you, by ${artist!!.name}"
                    gen > 0.3 -> "Because you like ${c.genre}"
                    else -> c.origin
                }
                Pick(SongKeys.displayTitle(c.title, c.artist), SongKeys.cleanArtist(c.artist, c.title), SongKeys.key(c.title, c.artist),
                    reason, Kind.SONG, 0.5 * art + 0.3 * gen + 0.15 * random.nextDouble(), c.mediaId, c.artUrl)
            }.filterNotNull().toList()
        return diversify(scored, limit, null, artistPenalty = 0.4)
    }

    // ---------------------------------------------------------------- parts

    /** How far each song is from the current one in your listening flow, 0..1. */
    private fun flowFrom(cur: String?, curArtist: String?): Map<String, Double> {
        val out = HashMap<String, Double>()
        if (cur != null) {
            val first = normalised(next[cur])
            for ((k, p) in first) {
                out.merge(k, p, Double::plus)
                for ((j, q) in normalised(next[k])) if (j != cur) out.merge(j, 0.5 * p * q, Double::plus)
            }
        }
        if (curArtist != null) {
            val byArtist = songs.values.groupBy { it.artistKey }
            for ((a, p) in normalised(artistNext[curArtist])) {
                val list = byArtist[a] ?: continue
                val total = list.sumOf { max(0.0, it.score) }.takeIf { it > 0 } ?: continue
                for (s in list) if (s.score > 0) out.merge(s.key, 0.35 * p * s.score / total, Double::plus)
            }
        }
        val top = out.values.maxOrNull()?.takeIf { it > 0 } ?: return out
        for (k in out.keys.toList()) out[k] = out.getValue(k) / top
        return out
    }

    private fun normalised(m: Map<String, Double>?): Map<String, Double> {
        if (m.isNullOrEmpty()) return emptyMap()
        val sum = m.values.sum().takeIf { it > 0 } ?: return emptyMap()
        return m.mapValues { it.value / sum }
    }

    /** How much more than usual this song belongs to this time of day, -1..1, shrunk while unsure. */
    private fun context(s: Song, slot: Int): Double {
        val own = s.slots.sum()
        if (own <= 0 || slotSum <= 0) return 0.0
        val a = 0.5
        val p = (s.slots[slot] + a) / (own + SLOTS * a)
        val base = (slotTotals[slot] + a) / (slotSum + SLOTS * a)
        return (ln(p / base)).coerceIn(-1.0, 1.0) * min(1.0, own / 4.0)
    }

    /** Skipped again and again: stop suggesting it. */
    private fun banned(s: Song) = s.bad >= 3 && s.good < s.bad * 0.4

    /** Greedy re-rank that spreads artists out, so one artist can't take the whole shelf. */
    private fun diversify(items: List<Pick>, limit: Int, avoidArtist: String?, artistPenalty: Double = 0.22): List<Pick> {
        if (limit <= 0) return emptyList()
        val pool = items.sortedByDescending { it.score }.take(limit * 6).toMutableList()
        val seen = HashMap<String, Int>()
        avoidArtist?.let { seen[it] = 1 }
        val out = ArrayList<Pick>(limit)
        while (out.size < limit && pool.isNotEmpty()) {
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in pool.indices) {
                val p = pool[i]
                val v = p.score - artistPenalty * (seen[artistOf(p)] ?: 0)
                if (v > bestScore) { bestScore = v; best = i }
            }
            val pick = pool.removeAt(best)
            out += pick
            seen.merge(artistOf(pick), 1, Int::plus)
        }
        return out
    }

    private fun artistOf(p: Pick) = if (p.key.startsWith("artist:")) p.key.removePrefix("artist:") else p.key.substringBefore('|')

    private fun sampleBeta(a: Double, b: Double): Double {
        val x = sampleGamma(a)
        val y = sampleGamma(b)
        return if (x + y <= 0) 0.5 else x / (x + y)
    }

    /** Marsaglia–Tsang; shape is always >= 1 here. */
    private fun sampleGamma(shape: Double): Double {
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do { x = gaussian(); v = 1.0 + c * x } while (v <= 0)
            v = v * v * v
            val u = random.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    companion object {
        const val SLOTS = 6
        val SLOT_NAMES = arrayOf("late night", "early morning", "morning", "afternoon", "evening", "night")
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS
        private const val WEEK_MS = 7 * DAY_MS
        private const val HALF_LIFE_MS = 30 * DAY_MS
        private const val SESSION_GAP_MS = 20 * MINUTE_MS
        /** Longer than this is a podcast, a mix or a video: not a song to learn from. */
        const val MAX_TRACK_MS = 20 * MINUTE_MS

        fun slotOf(hour: Int): Int = when (hour) {
            in 0..4 -> 0; in 5..8 -> 1; in 9..11 -> 2; in 12..16 -> 3; in 17..20 -> 4; else -> 5
        }

        /** -1 (skipped early) .. +1 (played through). */
        fun outcome(l: Listen): Double {
            val c = completion(l)
            return when {
                l.skipped && c < 0.5 -> -1.0
                l.skipped -> -0.2
                c >= 0.8 -> 1.0
                c >= 0.35 -> 0.4
                l.listenedMs < 30_000 -> -0.6
                else -> 0.0
            }
        }

        fun completion(l: Listen): Double =
            if (l.durationMs > 0) (l.listenedMs.toDouble() / l.durationMs).coerceIn(0.0, 1.0)
            else min(1.0, l.listenedMs / 180_000.0)

        private fun half(age: Long, halfLife: Long) = exp(-ln(2.0) * age / halfLife)

        private fun ago(ms: Long): String {
            val days = ms / DAY_MS
            return when {
                days >= 60 -> "${days / 30} months"
                days >= 14 -> "${days / 7} weeks"
                days >= 2 -> "$days days"
                else -> "a while"
            }
        }
    }
}

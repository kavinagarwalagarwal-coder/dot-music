package com.dotmusic.app

/**
 * One time a song was heard, as the recommendation engine learns from it. Kept on the phone only,
 * one line per listen in a small private file. Plain Kotlin, so the engine can be unit tested.
 */
data class Listen(
    /** Wall-clock time the song started (ms since epoch). */
    val startedAt: Long,
    val title: String,
    val artist: String,
    /** Package of the app that played it. */
    val source: String,
    val durationMs: Long,
    /** How long it actually played, pauses not counted. */
    val listenedMs: Long,
    /** Left early with Next or a flick. */
    val skipped: Boolean,
    /** Local hour of day 0..23 when it started. */
    val hour: Int,
    val weekend: Boolean,
    /** Dot's own player: where it came from ("youtube:…"), its cover, its genre. Empty for other apps. */
    val mediaId: String = "",
    val artUrl: String = "",
    val genre: String = "",
) {
    fun encode(): String = listOf(
        VERSION, startedAt.toString(), esc(title), esc(artist), esc(source), durationMs.toString(),
        listenedMs.toString(), if (skipped) "1" else "0", hour.toString(), if (weekend) "1" else "0",
        esc(mediaId), esc(artUrl), esc(genre),
    ).joinToString("\t")

    companion object {
        private const val VERSION = "2"

        fun decode(line: String): Listen? {
            val f = line.split('\t')
            if (f.size < 10 || (f[0] != "1" && f[0] != VERSION)) return null
            return runCatching {
                Listen(
                    startedAt = f[1].toLong(), title = unesc(f[2]), artist = unesc(f[3]), source = unesc(f[4]),
                    durationMs = f[5].toLong(), listenedMs = f[6].toLong(), skipped = f[7] == "1",
                    hour = f[8].toInt().coerceIn(0, 23), weekend = f[9] == "1",
                    mediaId = f.getOrNull(10)?.let(::unesc).orEmpty(),
                    artUrl = f.getOrNull(11)?.let(::unesc).orEmpty(),
                    genre = f.getOrNull(12)?.let(::unesc).orEmpty(),
                )
            }.getOrNull()
        }

        private fun esc(s: String) = buildString {
            for (ch in s) when (ch) {
                '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r")
                else -> append(ch)
            }
        }

        private fun unesc(s: String) = buildString {
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) { 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(s[i + 1]) }
                    i += 2
                } else { append(ch); i++ }
            }
        }
    }
}

/**
 * The same song shows up with different names: "Song (Official Video)", "Artist - Song",
 * "Song (feat. Someone)", "Artist - Topic", "ArtistVEVO". These turn them into one stable key.
 */
object SongKeys {
    private val bracket = Regex("""\s*[(\[【]([^)\]】]*)[)\]】]""")
    private val noise = Regex(
        """(?i)\b(official|video|audio|lyrics?|lyrical|visuali[sz]er|remaster(ed)?|hd|hq|4k|mv|m/v|full song|clip|feat|ft|featuring|explicit|clean)\b""")
    private val feat = Regex("""(?i)\s+[-–(]?\s*(feat\.?|ft\.?|featuring)\s+.*$""")
    private val artistSplit = Regex("""(?i)\s*(,|;|•|\s&\s|\sx\s|\s/\s|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s|\svs\.?\s)\s*""")
    private val topic = Regex("""(?i)\s*-\s*topic$""")
    private val vevo = Regex("""(?i)\s*vevo$""")
    private val dash = Regex("""\s+[-–—|]\s+""")

    /** "Artist - Song" video titles: the artist part, if there is one. */
    private fun splitDash(title: String): Pair<String, String>? {
        val m = dash.find(title) ?: return null
        val left = title.substring(0, m.range.first).trim()
        val right = title.substring(m.range.last + 1).trim()
        return if (left.isNotEmpty() && right.isNotEmpty()) left to right else null
    }

    /** The artist credit as it should read: no "- Topic", no "VEVO". */
    fun cleanArtist(artist: String, title: String = ""): String {
        var a = topic.replace(artist.trim(), "").let { vevo.replace(it, "") }.trim()
        // "Artist - Song" from a channel called "ArtistVEVO": the title spells the name properly.
        val dashed = splitDash(title)?.first
        if (dashed != null && (a.isEmpty() || compact(dashed) == compact(a))) a = dashed
        return a
    }

    /** The main artist only, for matching ("A, B & C" is A). */
    fun displayArtist(artist: String, title: String = ""): String {
        val a = cleanArtist(artist, title)
        return artistSplit.split(a).firstOrNull { it.isNotBlank() }?.trim() ?: a
    }

    fun displayTitle(title: String, artist: String = ""): String {
        var t = title.trim()
        splitDash(t)?.let { (left, right) ->
            val a = compact(displayArtist(artist, t))
            val l = compact(left)
            if (artist.isBlank() || (a.isNotEmpty() && (l.contains(a) || a.contains(l)))) t = right
        }
        t = bracket.replace(t) { m -> if (noise.containsMatchIn(m.groupValues[1])) "" else m.value }
        t = feat.replace(t, "")
        return t.trim().ifEmpty { title.trim() }
    }

    fun artistKey(artist: String, title: String = ""): String = norm(displayArtist(artist, title))

    fun key(title: String, artist: String): String = artistKey(artist, title) + "|" + norm(displayTitle(title, artist))

    /** Lowercase letters and digits of any script (vowel signs included), single-spaced. */
    fun norm(s: String): String {
        val out = StringBuilder(s.length)
        var space = false
        for (ch in s.lowercase()) {
            if (ch.isLetterOrDigit() || isMark(ch)) { if (space && out.isNotEmpty()) out.append(' '); out.append(ch); space = false }
            else space = true
        }
        return out.toString()
    }

    private fun isMark(ch: Char) = when (Character.getType(ch).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun compact(s: String) = norm(s).replace(" ", "")
}

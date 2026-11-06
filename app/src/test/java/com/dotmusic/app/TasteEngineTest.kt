package com.dotmusic.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TasteEngineTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3_600_000L
    private val dur = 200_000L

    private fun listen(at: Long, title: String, artist: String, completion: Double = 1.0, skipped: Boolean = false, hour: Int = 12) =
        Listen(at, title, artist, "com.google.android.apps.youtube.music", dur, (dur * completion).toLong(), skipped, hour, weekend = false)

    /** Songs played back to back, one session. */
    private fun session(start: Long, vararg songs: Pair<String, String>, hour: Int = 12): List<Listen> {
        var t = start
        return songs.map { (title, artist) -> listen(t, title, artist, hour = hour).also { t += dur + 5_000 } }
    }

    private fun engine(listens: List<Listen>, seed: Int = 7) = TasteEngine(listens, now, Random(seed))

    @Test fun listensSurviveTheFileEvenWithTabsAndNewlines() {
        val l = Listen(123L, "Tab\there\nnew\\line", "Ärtist 名前", "pkg", 1000, 500, true, 23, true)
        assertEquals(l, Listen.decode(l.encode()))
        assertEquals(null, Listen.decode("garbage"))
    }

    @Test fun videoTitlesAndTopicChannelsBecomeTheSameSong() {
        val key = SongKeys.key("Blinding Lights", "The Weeknd")
        assertEquals(key, SongKeys.key("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals(key, SongKeys.key("The Weeknd - Blinding Lights (Official Video)", ""))
        assertEquals(key, SongKeys.key("Blinding Lights", "The Weeknd - Topic"))
        assertEquals(key, SongKeys.key("Blinding Lights [Official Audio]", "The Weeknd"))
        assertEquals(SongKeys.key("Peaches", "Justin Bieber"), SongKeys.key("Peaches (feat. Daniel Caesar & Giveon)", "Justin Bieber, Daniel Caesar"))
        // Non-Latin titles keep their letters.
        assertEquals("तुम ही हो", SongKeys.norm("तुम  ही हो!"))
        // A dash that's part of the song name stays.
        assertEquals("Love - Remix", SongKeys.displayTitle("Love - Remix", "Someone"))
        assertEquals("Lil Nas X", SongKeys.displayArtist("Lil Nas X"))
    }

    @Test fun whatUsuallyComesNextComesNext() {
        val listens = ArrayList<Listen>()
        for (d in 3..9) {
            listens += session(now - d * day, "Alpha" to "Ann", "Bravo" to "Ben")
            listens += session(now - d * day + 3 * 3_600_000L, "Charlie" to "Cat")
            listens += session(now - d * day + 6 * 3_600_000L, "Delta" to "Dan")
        }
        repeat(10) { seed ->
            val up = engine(listens, seed).upNext("Alpha", "Ann", hour = 12)
            assertEquals("Bravo", up.first().title)
            assertEquals("Often follows Alpha", up.first().reason)
            assertFalse(up.any { it.title == "Alpha" })
        }
    }

    @Test fun songsYouKeepSkippingStopComingUp() {
        val listens = ArrayList<Listen>()
        for (d in 2..7) listens += listen(now - d * day, "Loved", "Ann")
        for (d in 2..5) listens += listen(now - d * day + 1_000_000, "Meh", "Ben", completion = 0.05, skipped = true)
        for (d in 2..3) listens += listen(now - d * day + 2_000_000, "Okay", "Cat", completion = 0.5)
        var ahead = 0
        repeat(20) { seed ->
            val up = engine(listens, seed).upNext(null, null, hour = 12)
            assertFalse("A song skipped four times was suggested", up.any { it.title == "Meh" })
            if (up.indexOfFirst { it.title == "Loved" } < up.indexOfFirst { it.title == "Okay" }) ahead++
        }
        assertTrue("Loved should usually beat a half-listened song ($ahead/20)", ahead >= 17)
    }

    @Test fun aSongYouJustHeardRestsForAWhile() {
        val listens = ArrayList<Listen>()
        for (d in 5..9) { listens += listen(now - d * day, "Just played", "Ann"); listens += listen(now - d * day + 900_000, "Fresh", "Ben") }
        listens += listen(now - 10 * 60_000L, "Just played", "Ann")
        repeat(5) { seed -> assertEquals("Fresh", engine(listens, seed).upNext(null, null, 12).first().title) }
    }

    @Test fun oneArtistCantTakeTheWholeShelf() {
        val listens = ArrayList<Listen>()
        for (d in 2..8) for (i in 1..6) listens += listen(now - d * day + i * 1_000_000L, "Hit $i", "Superstar")
        for (d in 2..4) { listens += listen(now - d * day + 9_000_000, "Other", "Ann"); listens += listen(now - d * day + 9_500_000, "Another", "Ben") }
        val top4 = engine(listens).upNext(null, null, 12).take(4)
        assertTrue("Top four were all one artist: $top4", top4.count { it.artist == "Superstar" } <= 3)
    }

    @Test fun nightSongsAtNightMorningSongsInTheMorning() {
        val listens = ArrayList<Listen>()
        for (d in 1..6) {
            listens += listen(now - d * day, "Midnight", "Ann", hour = 23)
            listens += listen(now - d * day + 600_000, "Sunrise", "Ben", hour = 7)
        }
        val e = engine(listens)
        assertTrue(e.forNow(hour = 22).any { it.title == "Midnight" })
        assertFalse(e.forNow(hour = 22).any { it.title == "Sunrise" })
        assertTrue(e.forNow(hour = 6).any { it.title == "Sunrise" })
    }

    @Test fun oldFavouritesComeBack() {
        val listens = ArrayList<Listen>()
        for (d in 60..65) listens += listen(now - d * day, "Old flame", "Ann")
        for (d in 1..5) listens += listen(now - d * day, "Current", "Ben")
        val r = engine(listens).rediscover()
        assertTrue(r.any { it.title == "Old flame" && it.reason.startsWith("Last played") })
        assertFalse(r.any { it.title == "Current" })
    }

    @Test fun exploreFindsArtistsThatFollowYourFavourites() {
        val listens = ArrayList<Listen>()
        for (d in 2..9) listens += session(now - d * day, "Big one" to "Main", "Deep cut" to "Main")
        for (d in 2..3) listens += session(now - d * day + 3_600_000, "Big one" to "Main", "Side" to "Newer")
        val ex = engine(listens).explore()
        val artist = ex.firstOrNull { it.kind == TasteEngine.Kind.ARTIST }
        assertNotNull("No artist to explore in $ex", artist)
        assertEquals("Newer", artist!!.artist)
        assertEquals("Newer", artist.query)
        assertTrue(ex.any { it.kind == TasteEngine.Kind.SONG && it.query == "Big one Main" })
    }

    @Test fun podcastsAndBlipsDontCount() {
        val pod = Listen(now - day, "Episode 12", "A podcast", "pod", 60 * 60_000L, 60 * 60_000L, false, 9, false)
        val e = engine(listOf(pod))
        assertTrue(e.isEmpty)
        assertTrue(e.shelves(null, null, 9).upNext.isEmpty())
    }
}

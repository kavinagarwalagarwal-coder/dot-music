package com.dotmusic.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CatalogParseTest {
    @Test fun youtubeResultsBecomeCleanSongs() {
        val json = """
        {"items":[
          {"id":{"kind":"youtube#video","videoId":"4NRXx6U8ABQ"},
           "snippet":{"title":"The Weeknd - Blinding Lights (Official Video)","channelTitle":"TheWeekndVEVO",
             "liveBroadcastContent":"none",
             "thumbnails":{"default":{"url":"https://i.ytimg.com/vi/4NRXx6U8ABQ/default.jpg"},
                           "high":{"url":"https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg"}}}},
          {"id":{"kind":"youtube#video","videoId":"BddP6PYo2gs"},
           "snippet":{"title":"Kesariya - Brahmāstra | Arijit Singh &amp; Pritam","channelTitle":"Sony Music India",
             "liveBroadcastContent":"none","thumbnails":{}}},
          {"id":{"kind":"youtube#video","videoId":"liveLIVE123"},
           "snippet":{"title":"lofi radio 24/7","channelTitle":"Lofi Girl","liveBroadcastContent":"live"}},
          {"id":{"kind":"youtube#channel","channelId":"UC123"},"snippet":{"title":"A channel"}}
        ]}""".trimIndent()
        val r = CatalogParse.youtube(json)
        assertEquals(2, r.size)
        assertEquals("Blinding Lights", r[0].title)
        assertEquals("The Weeknd", r[0].artist)
        assertEquals("youtube:4NRXx6U8ABQ", r[0].mediaId)
        assertEquals("https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", r[0].artUrl)
        assertTrue(r[1].title.contains("&") && !r[1].title.contains("&amp;"))
    }

    @Test fun audiusAndJamendoParse() {
        val audius = """{"data":[{"id":"D7KyD","title":"Sunset","duration":215,"genre":"Electronic",
            "user":{"name":"Skyline"},"artwork":{"150x150":"a150","480x480":"a480"}},
            {"id":"X","title":"Locked","duration":100,"is_streamable":false,"user":{"name":"N"}}]}"""
        val a = CatalogParse.audius(audius)
        assertEquals(1, a.size)
        assertEquals(OnlineTrack(OnlineTrack.Source.AUDIUS, "D7KyD", "Sunset", "Skyline", "a480", 215_000, "Electronic"), a[0])

        val jamendo = """{"headers":{"status":"success"},"results":[{"id":"1532771","name":"Calm","duration":180,
            "artist_name":"Ana","image":"img","audio":"https://prod-1.storage.jamendo.com/?trackid=1532771&format=mp32",
            "musicinfo":{"tags":{"genres":["ambient","electronic"]}}},{"id":"2","name":"No audio","audio":""}]}"""
        val j = CatalogParse.jamendo(jamendo)
        assertEquals(1, j.size)
        assertEquals("jamendo:1532771", j[0].mediaId)
        assertEquals("ambient", j[0].genre)
        assertTrue(j[0].streamUrl.startsWith("https://"))
    }

    @Test fun mergeInterleavesAndDropsDuplicates() {
        fun t(s: OnlineTrack.Source, id: String, title: String, artist: String) = OnlineTrack(s, id, title, artist, "")
        val yt = listOf(t(OnlineTrack.Source.YOUTUBE, "a", "Song A", "X"), t(OnlineTrack.Source.YOUTUBE, "b", "Song B", "Y"))
        val au = listOf(t(OnlineTrack.Source.AUDIUS, "1", "Song A", "X"), t(OnlineTrack.Source.AUDIUS, "2", "Song C", "Z"))
        val m = CatalogParse.merge(yt, au)
        assertEquals(listOf("youtube:a", "youtube:b", "audius:2"), m.map { it.mediaId })
        assertEquals("youtube:b", CatalogParse.best(m, "Song B", "Y")!!.mediaId)
        assertEquals("youtube:a", CatalogParse.best(m, "Nope", "Nobody")!!.mediaId)
        assertNull(CatalogParse.best(emptyList(), "a", "b"))
    }

    @Test fun mediaIdsRoundTrip() {
        val t = OnlineTrack.fromMediaId("audius:D7KyD", "Sunset", "Skyline")!!
        assertEquals(OnlineTrack.Source.AUDIUS, t.source)
        assertEquals("D7KyD", t.id)
        assertNull(OnlineTrack.fromMediaId("spotify:123", "a", "b"))
        assertNull(OnlineTrack.fromMediaId("youtube:", "a", "b"))
        assertEquals("Tom & Jerry's \"best\" <3 😀", CatalogParse.entities("Tom &amp; Jerry&#39;s &quot;best&quot; &lt;3 &#x1F600;"))
    }

    @Test fun listensRememberWhereSongsCameFrom() {
        val l = Listen(1L, "Sunset", "Skyline", "dot:audius", 215_000, 200_000, false, 21, false,
            mediaId = "audius:D7KyD", artUrl = "a480", genre = "Electronic")
        assertEquals(l, Listen.decode(l.encode()))
        // Old (v1) lines still load.
        val v1 = "1\t5\tOld\tArtist\tpkg\t1000\t900\t0\t9\t0"
        assertEquals("Old", Listen.decode(v1)!!.title)
        assertEquals("", Listen.decode(v1)!!.mediaId)
    }

    @Test fun picksCarryTheirSourceAndDiscoverRanksByTaste() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        val listens = (1..6).flatMap { d ->
            listOf(
                Listen(now - d * day, "Sunset", "Skyline", "dot:audius", 200_000, 200_000, false, 20, false, "audius:S1", "art1", "Electronic"),
                Listen(now - d * day + 600_000, "Rain", "Cloud", "dot:audius", 200_000, 200_000, false, 20, false, "audius:R1", "art2", "Electronic"),
            )
        }
        val e = TasteEngine(listens, now, Random(3))
        val pick = e.upNext("Sunset", "Skyline", 20).first { it.title == "Rain" }
        assertEquals("audius:R1", pick.mediaId)
        assertEquals("electronic", e.topGenre())

        val ranked = e.rankNew(listOf(
            TasteEngine.Candidate("Sunset", "Skyline", "Electronic", "audius:S1", "", "Trending on Audius"),
            TasteEngine.Candidate("New one", "Skyline", "Pop", "audius:N1", "", "Trending on Audius"),
            TasteEngine.Candidate("Bass", "Stranger", "Electronic", "audius:B1", "", "Trending on Audius"),
            TasteEngine.Candidate("Ballad", "Stranger2", "Country", "audius:C1", "", "Trending on Audius"),
        ))
        assertTrue("Already-heard songs aren't new", ranked.none { it.title == "Sunset" })
        assertEquals("New one", ranked[0].title)
        assertEquals("New to you, by Skyline", ranked[0].reason)
        assertEquals("Because you like Electronic", ranked.first { it.title == "Bass" }.reason)
        assertEquals("audius:B1", ranked.first { it.title == "Bass" }.mediaId)
    }
}

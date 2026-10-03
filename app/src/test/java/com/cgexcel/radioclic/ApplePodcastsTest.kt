/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import com.cgexcel.radioclic.net.ApplePodcasts
import com.cgexcel.radioclic.net.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ApplePodcastsTest {

    private val html = """
        <html><head>
        <meta property="og:title" content="Journal de 08h00 | Podcast on Apple Podcasts">
        <meta property="og:image" content="https://example.org/cover.jpg">
        </head><body>
        <script type="application/json" id="serialized-server-data">[{"data":{"shelves":[{"items":[
          {"title":"Le journal de 08h00 du vendredi 02 octobre 2026","releaseDate":"2026-10-02T06:00:00Z",
           "mediaEnclosures":[{"streamUrl":"https://audio.example.org/0210.mp3","duration":1187}]},
          {"primaryButtonAction":{"episodeOffer":{
             "episodeArtwork":{"template":"https://img.example.org/{w}x{h}bb.{f}"},
             "title":"Le journal de 08h00 du samedi 03 octobre 2026","releaseDate":"2026-10-03T06:00:00Z",
             "mediaEnclosures":[{"streamUrl":"https://audio.example.org/0310.mp3"}],
             "currentMediaEnclosure":{"streamUrl":"https://audio.example.org/0310.mp3"}}}},
          {"title":"Émission sans audio","releaseDate":"2026-10-03T07:00:00Z"}
        ]}]}}]</script>
        </body></html>
    """.trimIndent()

    @Test
    fun latestEpisodeComesFirst() {
        val feed = ApplePodcasts.parse(html)
        assertEquals("Journal de 08h00", feed.title)
        assertEquals(2, feed.episodes.size)
        val latest = feed.episodes.first()
        assertEquals("Le journal de 08h00 du samedi 03 octobre 2026", latest.title)
        assertEquals("https://audio.example.org/0310.mp3", latest.audioUrl)
        assertEquals("https://img.example.org/600x600bb.jpg", latest.imageUrl)
    }

    @Test
    fun parsesRssAndIsoDates() {
        assertNotNull(RssParser.parseDate("Fri, 02 Oct 2026 12:30:00 +0200"))
        assertNotNull(RssParser.parseDate("Sat, 3 Oct 2026 08:00:00 GMT"))
        assertEquals(
            RssParser.parseDate("2026-10-03T06:00:00Z"),
            RssParser.parseDate("Sat, 03 Oct 2026 08:00:00 +0200"),
        )
    }
}

class AdFreeTest {
    @Test
    fun proxycastLinkPointsToOriginalFile() {
        val proxy = "https://proxycast.radiofrance.fr/3509/7241/ea9f/21003-03.10.2026-ITEMA_24697040-22-059e.mp3"
        assertEquals(
            "https://media.radiofrance-podcast.net/podcast09/21003-03.10.2026-ITEMA_24697040-22-059e.mp3",
            com.cgexcel.radioclic.net.AdFree.directUrl(proxy),
        )
        assertEquals(null, com.cgexcel.radioclic.net.AdFree.directUrl("https://example.org/episode.mp3"))
    }
}

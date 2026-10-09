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

class RadioFranceFeedTest {
    private val resolver = com.cgexcel.radioclic.net.FeedResolver

    @Test
    fun showIdentifierGivesOfficialFeed() {
        val show = "6f03e972-cada-490c-a455-cb8223327109"
        val urls = listOf(
            "https://proxycast.radiofrance.fr/35099478-7c72-4f9e-a6de-1b928400e9e5/$show/296e3f12-df48-4080-96d1-c49e29b750c5/21713-08.10.2026-ITEMA_24703247-2026F8770S0281-NET_MFI_8FFE8F4F-22-d3b1.mp3",
            "https://proxycast.radiofrance.fr/35099478-7c72-4f9e-a6de-1b928400e9e5/$show/86719677-3a59-40c3-a7c7-3975f6b2fabd/25655-07.10.2026-ITEMA_24702581.mp3",
        )
        assertEquals("https://radiofrance-podcast.net/podcast09/podcast_$show.xml", resolver.radioFranceFeedUrl(urls))
        assertEquals(null, resolver.radioFranceFeedUrl(listOf("https://audio.audiomeans.fr/file/PcNCvtcnHj/fdb1e4ab.mp3")))
        assertEquals(null, resolver.radioFranceFeedUrl(listOf("https://proxycast.radiofrance.fr/3509/7241/ea9f/21003-03.10.2026-ITEMA_24697040-22-059e.mp3")))
    }

    @Test
    fun feedMustBeTheSameShow() {
        fun feed(title: String, vararg episodes: String) = com.cgexcel.radioclic.model.Feed(
            title, null, episodes.map { com.cgexcel.radioclic.model.Episode(title = it, audioUrl = "https://a/$it", pubDate = null, imageUrl = null) },
        )
        assertEquals(true, resolver.sameShow(feed("L'édito éco", "A"), feed("L’édito éco", "B")))
        assertEquals(true, resolver.sameShow(feed("Podcast", "Carburants : un geste malin"), feed("Autre", "Carburants : un geste malin ")))
        assertEquals(false, resolver.sameShow(feed("L'édito éco", "A"), feed("Vous avez moins de 5 minutes ?", "Retrouvez tous les épisodes")))
    }
}

class UpdaterTest {
    @Test
    fun readsLatestRelease() {
        val json = """
            {"tag_name":"v1.0.31","body":"Correctifs",
             "assets":[{"name":"notes.txt","browser_download_url":"https://github.com/x/notes.txt"},
                       {"name":"RadioClic-1.0.31.apk","browser_download_url":"https://github.com/x/RadioClic-1.0.31.apk"}]}
        """.trimIndent()
        val release = com.cgexcel.radioclic.net.Updater.parse(json)!!
        assertEquals(31, release.versionCode)
        assertEquals("1.0.31", release.versionName)
        assertEquals("https://github.com/x/RadioClic-1.0.31.apk", release.apkUrl)
        assertEquals(null, com.cgexcel.radioclic.net.Updater.parse("""{"tag_name":"v1.0.32","assets":[]}"""))
    }
}

class AdFreeLiveTest {
    @Test
    fun radioFranceLiveUsesHls() {
        val hls = "https://stream.radiofrance.fr/franceinter/franceinter_hifi.m3u8?id=radiofrance"
        assertEquals(hls, com.cgexcel.radioclic.net.AdFree.liveHlsUrl("https://icecast.radiofrance.fr/franceinter-hifi.aac"))
        assertEquals(hls, com.cgexcel.radioclic.net.AdFree.liveHlsUrl("http://direct.franceinter.fr/live/franceinter-midfi.mp3"))
        assertEquals(
            "https://stream.radiofrance.fr/fbtoulouse/fbtoulouse_hifi.m3u8?id=radiofrance",
            com.cgexcel.radioclic.net.AdFree.liveHlsUrl("https://icecast.radiofrance.fr/fbtoulouse-midfi.mp3?ID=radiofrance"),
        )
        assertEquals(null, com.cgexcel.radioclic.net.AdFree.liveHlsUrl("https://direct.radiopresence.com/presence"))
    }
}

class LiveTimeshiftTest {
    private val playlist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA-SEQUENCE:2355081
        #EXT-X-TARGETDURATION:4
        #EXT-X-START:TIME-OFFSET=0
        #EXT-X-PROGRAM-DATE-TIME:2026-10-03T12:42:20Z
        #EXTINF:4.000,
        /accs3/franceinter/prod1transcoder2/franceinter_aac_hifi_4_2355081_1791031340.ts?id=radiofrance
        #EXT-X-PROGRAM-DATE-TIME:2026-10-03T12:42:24Z
        #EXTINF:4.000,
        /accs3/franceinter/prod1transcoder2/franceinter_aac_hifi_4_2355082_1791031344.ts?id=radiofrance
    """.trimIndent()

    @Test
    fun addsOlderSegments() {
        val out = com.cgexcel.radioclic.playback.LiveTimeshift.extend(
            playlist,
            "https://stream.radiofrance.fr/franceinter/franceinter_hifi.m3u8?id=radiofrance",
            60,
        ).lines()
        assert(out.contains("#EXT-X-MEDIA-SEQUENCE:2355066"))
        assert(out.none { it.startsWith("#EXT-X-START") })
        assert(out.contains("https://stream.radiofrance.fr/accs3/franceinter/prod1transcoder2/franceinter_aac_hifi_4_2355066_1791031280.ts?id=radiofrance"))
        assert(out.contains("#EXT-X-PROGRAM-DATE-TIME:2026-10-03T12:41:20Z"))
        assert(out.contains("https://stream.radiofrance.fr/accs3/franceinter/prod1transcoder2/franceinter_aac_hifi_4_2355082_1791031344.ts?id=radiofrance"))
        assertEquals(17, out.count { it.startsWith("#EXTINF") })
    }
}

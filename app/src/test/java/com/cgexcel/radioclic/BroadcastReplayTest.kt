/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import com.cgexcel.radioclic.model.Episode
import com.cgexcel.radioclic.model.Feed
import com.cgexcel.radioclic.net.RssParser
import com.cgexcel.radioclic.playback.BroadcastReplay
import com.cgexcel.radioclic.playback.LiveTimeshift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

class BroadcastReplayTest {

    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    /** Journal de 8h : du mardi 6 au lundi 12 octobre 2026, 18 min environ. */
    private val journal = Feed(
        title = "Journal de 08h00",
        imageUrl = null,
        author = "France Inter",
        episodes = (6..12).reversed().map { day ->
            Episode(
                title = "Journal du $day",
                audioUrl = "https://a/$day.mp3",
                pubDate = at("2026-10-%02dT08:00:01+02:00".format(day)).toEpochMilli(),
                imageUrl = null,
                durationSec = 18 * 60,
            )
        },
    )

    @Test
    fun durationsAndStations() {
        assertEquals(1092, RssParser.parseDuration("00:18:12"))
        assertEquals(199, RssParser.parseDuration("03:19"))
        assertEquals(42, RssParser.parseDuration("42"))
        assertNull(RssParser.parseDuration("bientôt"))
        assertEquals("franceinter", BroadcastReplay.stationFor("France Inter"))
        assertEquals("franceinfo", BroadcastReplay.stationFor("franceinfo"))
        assertNull(BroadcastReplay.stationFor("Radio Classique"))
    }

    @Test
    fun replaysTodaysBroadcastFromTheLiveStream() {
        // Mardi 13 à 8h10 : l'épisode du jour n'est pas encore en ligne.
        val plan = BroadcastReplay.plan(journal, at("2026-10-13T08:10:00+02:00"))!!
        assertEquals("franceinter", plan.station)
        assertEquals(at("2026-10-13T08:00:01+02:00").epochSecond, plan.startSec)
        assertEquals(plan.startSec + 18 * 60 + 20, plan.endSec)
    }

    @Test
    fun noReplayWhenNotNeededOrNotPossible() {
        // Avant 8h : pas encore diffusé.
        assertNull(BroadcastReplay.plan(journal, at("2026-10-13T07:30:00+02:00")))
        // Épisode du jour déjà en ligne.
        assertNull(BroadcastReplay.plan(journal, at("2026-10-12T09:00:00+02:00")))
        // Station inconnue.
        assertNull(BroadcastReplay.plan(journal.copy(author = "Radio Classique"), at("2026-10-13T08:10:00+02:00")))
        // Horaire instable.
        val shifted = journal.copy(
            episodes = journal.episodes.mapIndexed { i, e -> e.copy(pubDate = e.pubDate!! + i * 3_600_000L) },
        )
        assertNull(BroadcastReplay.plan(shifted, at("2026-10-13T08:10:00+02:00")))
        // Jamais diffusé ce jour de la semaine (que du lundi au vendredi ici, et on est samedi).
        val weekdays = journal.copy(episodes = journal.episodes.filter { e ->
            Instant.ofEpochMilli(e.pubDate!!).atZone(java.time.ZoneId.of("Europe/Paris")).dayOfWeek.value <= 5
        })
        assertNull(BroadcastReplay.plan(weekdays, at("2026-10-17T08:30:00+02:00")))
    }

    private val live = """
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

    private val url = "https://stream.radiofrance.fr/franceinter/franceinter_hifi.m3u8?id=radiofrance"

    @Test
    fun finishedSlotIsAFullPlaylist() {
        // De 12h40:01 à 12h41:00 (UTC), terminé.
        val out = LiveTimeshift.replay(live, url, 1791031201, 1791031260).lines()
        assertEquals("#EXT-X-MEDIA-SEQUENCE:2355046", out.first { it.startsWith("#EXT-X-MEDIA-SEQUENCE") })
        assert(out.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assert(out.contains("#EXT-X-ENDLIST"))
        assert(out.contains("#EXT-X-START:TIME-OFFSET=1,PRECISE=YES"))
        assert(out.contains("https://stream.radiofrance.fr/accs3/franceinter/prod1transcoder2/franceinter_aac_hifi_4_2355046_1791031200.ts?id=radiofrance"))
        assert(out.contains("#EXT-X-PROGRAM-DATE-TIME:2026-10-03T12:40:00Z"))
        assertEquals(15, out.count { it.startsWith("#EXTINF") })
    }

    @Test
    fun ongoingSlotGrows() {
        val out = LiveTimeshift.replay(live, url, 1791031300, 1791032000).lines()
        assert(out.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assert(out.none { it == "#EXT-X-ENDLIST" })
        assert(out.last { it.isNotEmpty() }.endsWith("franceinter_aac_hifi_4_2355082_1791031344.ts?id=radiofrance"))
        assertEquals(12, out.count { it.startsWith("#EXTINF") })
    }
}

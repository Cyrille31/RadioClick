/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.model.Tile
import com.cgexcel.radioclic.net.Http
import com.cgexcel.radioclic.net.AdFree
import com.cgexcel.radioclic.net.FeedResolver
import com.cgexcel.radioclic.net.toUserMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Construit l'enchaînement d'une tuile au moment de l'appui. */
@OptIn(UnstableApi::class)
object PlaylistBuilder {

    /** Clé des extras de métadonnées : durée maximale (ms) d'un direct. */
    const val EXTRA_MAX_DURATION_MS = "radioclic.maxDurationMs"

    /** Clés des extras d'un direct réparable : identifiant de l'élément et nom de la station. */
    const val EXTRA_LIVE_ITEM_ID = "radioclic.liveItemId"
    const val EXTRA_STATION_NAME = "radioclic.stationName"

    class Result(val mediaItems: List<MediaItem>, val messages: List<String>)

    private sealed class Resolved {
        class Ok(val item: MediaItem) : Resolved()
        class Skipped(val message: String) : Resolved()
    }

    /** Résout tous les éléments en parallèle, puis les garde dans l'ordre. */
    /** Réglages de lecture utiles à la construction. */
    data class Options(
        val skipAds: Boolean = true,
        val liveRewindMinutes: Int = 0,
        /** Jour choisi dans le planning ; null : le plus récent (comportement habituel). */
        val day: LocalDate? = null,
    )

    /** Clé des extras : élément repris dans le direct (créneau passé, pas un vrai direct). */
    const val EXTRA_REPLAY = "radioclic.replay"

    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    /** Jour demandé s'il n'est pas aujourd'hui (heure de Paris), sinon null. */
    private fun pastDay(options: Options): LocalDate? =
        options.day?.takeIf { it != LocalDate.now(paris) }

    suspend fun build(tile: Tile, options: Options = Options()): Result = coroutineScope {
        val resolved = tile.items.mapIndexed { index, item ->
            async { resolve(tile, item, index, options) }
        }.awaitAll()
        Result(
            mediaItems = resolved.filterIsInstance<Resolved.Ok>().map { it.item },
            messages = resolved.filterIsInstance<Resolved.Skipped>().map { it.message },
        )
    }

    private suspend fun resolve(tile: Tile, item: PlayItem, index: Int, options: Options): Resolved {
        val label = item.title.ifBlank { "Élément ${index + 1}" }
        return try {
            when (item) {
                is PlayItem.Podcast -> resolvePodcast(tile, item, label, index, options)
                is PlayItem.Live -> pastDay(options)
                    ?.let { Resolved.Skipped("« $label » : direct ignoré pour un autre jour") }
                    ?: resolveLive(tile, item, label, index, options)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Resolved.Skipped("« $label » sauté : ${e.toUserMessage()}")
        }
    }

    private suspend fun resolvePodcast(
        tile: Tile,
        item: PlayItem.Podcast,
        label: String,
        index: Int,
        options: Options,
    ): Resolved {
        val skipAds = options.skipAds
        if (!item.hasSource) return Resolved.Skipped("« $label » sauté : émission non choisie")
        val feed = FeedResolver.fetchWithCompanions(item.feedUrl, item.appleId)
        val past = pastDay(options)
        val episode = if (past != null) {
            // Planning : l'épisode paru ce jour-là (le dernier du jour s'il y en a plusieurs).
            feed.episodes.firstOrNull { e -> e.pubDate?.let { Instant.ofEpochMilli(it).atZone(paris).toLocalDate() } == past }
                ?: return Resolved.Skipped("« $label » : pas d'épisode le ${dayFormat.format(past)}")
        } else {
            feed.episodes.firstOrNull()
                ?: return Resolved.Skipped("« $label » sauté : aucun épisode dans le flux")
        }
        // Épisode du jour pas encore en ligne, mais émission déjà diffusée : reprise dans le direct.
        if (past == null) BroadcastReplay.plan(feed)?.let { plan ->
            return Resolved.Ok(replayItem(tile, item, label, index, plan, item.imageUrl ?: feed.imageUrl))
        }
        if (past == null && item.onlyToday && !isToday(episode.pubDate)) {
            return Resolved.Skipped("« $label » pas encore en ligne")
        }
        // La date et l'heure de l'épisode sont toujours affichées, quand le flux les donne.
        val dated = episode.pubDate?.let { "${shortDate(it)} · ${episode.title}" } ?: episode.title
        val metadata = MediaMetadata.Builder()
            .setTitle(label)
            .setArtist(dated)
            .setAlbumTitle(tile.title)
            .setArtworkUri((episode.imageUrl ?: item.imageUrl ?: feed.imageUrl)?.let(Uri::parse))
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()
        return Resolved.Ok(
            MediaItem.Builder()
                .setMediaId("${item.id}#$index")
                .setUri(if (skipAds) AdFree.resolve(episode.audioUrl) else episode.audioUrl)
                .setMediaMetadata(metadata)
                .build(),
        )
    }

    private fun replayItem(
        tile: Tile,
        item: PlayItem.Podcast,
        label: String,
        index: Int,
        plan: BroadcastReplay.Plan,
        image: String?,
    ): MediaItem {
        val start = Instant.ofEpochSecond(plan.startSec).atZone(ZoneId.systemDefault())
        val metadata = MediaMetadata.Builder()
            .setTitle(label)
            .setArtist("Aujourd'hui ${timeFormat.format(start)} · repris du direct")
            .setAlbumTitle(tile.title)
            .setArtworkUri(image?.let(Uri::parse))
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setExtras(Bundle().apply { putBoolean(EXTRA_REPLAY, true) })
            .build()
        return MediaItem.Builder()
            .setMediaId("${item.id}#$index")
            .setUri(LiveTimeshift.replayUrl(plan.station, plan.startSec, plan.endSec))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(metadata)
            .build()
    }

    private val timeFormat = DateTimeFormatter.ofPattern("HH'h'mm", Locale.FRENCH)

    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

    private suspend fun resolveLive(
        tile: Tile,
        item: PlayItem.Live,
        label: String,
        index: Int,
        options: Options,
    ): Resolved {
        if (item.streamUrl.isBlank()) return Resolved.Skipped("« $label » sauté : aucune adresse de flux")
        val original = item.streamUrl.trim()
        val rewind = options.liveRewindMinutes
        // Flux HLS Radio France : sans publicité au lancement, et permet de reculer dans le direct.
        val hls = when {
            LiveTimeshift.isRadioFranceHls(original) -> original
            options.skipAds || rewind > 0 -> AdFree.resolveLive(original).takeIf { it != original }
            else -> null
        }
        val direct = if (hls == null) resolveStreamPlaylist(original) else null
        // Autres radios : flux MP3 / AAC enregistré pendant l'écoute pour pouvoir reculer.
        val buffered = direct != null && rewind > 0 && LiveBuffer.probe(direct) != null
        val url = when {
            hls != null && rewind > 0 -> LiveTimeshift.wrap(hls, rewind)
            hls != null -> hls
            buffered -> LiveBuffer.playlistUrl(direct!!, rewind)
            else -> direct!!
        }
        val extras = Bundle()
        item.maxMinutes?.takeIf { it > 0 }?.let { extras.putLong(EXTRA_MAX_DURATION_MS, it * 60_000L) }
        if (hls == null && !buffered) {
            // Flux ordinaire : s'il a été déplacé, le service pourra le retrouver (StreamRepair).
            extras.putString(EXTRA_LIVE_ITEM_ID, item.id)
            extras.putString(EXTRA_STATION_NAME, label)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(label)
            .setArtist("En direct")
            .setAlbumTitle(tile.title)
            .setArtworkUri(item.imageUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setExtras(extras)
            .build()
        return Resolved.Ok(
            MediaItem.Builder()
                .setMediaId("${item.id}#$index")
                .setUri(url)
                .apply { if (hls != null || buffered) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setMediaMetadata(metadata)
                .build(),
        )
    }

    private val shortDateFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH'h'mm", Locale.FRENCH)

    private fun shortDate(millis: Long): String =
        shortDateFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** Les fichiers .pls / .m3u ne sont pas lus directement : on en extrait la première URL. */
    private suspend fun resolveStreamPlaylist(url: String): String {
        val path = Uri.parse(url).path?.lowercase() ?: return url
        if (!path.endsWith(".pls") && !path.endsWith(".m3u")) return url
        val text = Http.getText(url)
        return text.lineSequence()
            .map { it.trim() }
            .map { line -> if (line.startsWith("http", ignoreCase = true)) line else line.substringAfter('=', "").trim() }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?: throw IOException("liste de lecture radio vide")
    }

    private fun isToday(pubDate: Long?): Boolean {
        if (pubDate == null) return false
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(pubDate).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }
}

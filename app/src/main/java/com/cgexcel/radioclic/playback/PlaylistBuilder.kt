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
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.model.Tile
import com.cgexcel.radioclic.net.Http
import com.cgexcel.radioclic.net.RssParser
import com.cgexcel.radioclic.net.toUserMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Construit l'enchaînement d'une tuile au moment de l'appui. */
@OptIn(UnstableApi::class)
object PlaylistBuilder {

    /** Clé des extras de métadonnées : durée maximale (ms) d'un direct. */
    const val EXTRA_MAX_DURATION_MS = "radioclic.maxDurationMs"

    class Result(val mediaItems: List<MediaItem>, val messages: List<String>)

    private sealed class Resolved {
        class Ok(val item: MediaItem) : Resolved()
        class Skipped(val message: String) : Resolved()
    }

    /** Résout tous les éléments en parallèle, puis les garde dans l'ordre. */
    suspend fun build(tile: Tile): Result = coroutineScope {
        val resolved = tile.items.mapIndexed { index, item ->
            async { resolve(tile, item, index) }
        }.awaitAll()
        Result(
            mediaItems = resolved.filterIsInstance<Resolved.Ok>().map { it.item },
            messages = resolved.filterIsInstance<Resolved.Skipped>().map { it.message },
        )
    }

    private suspend fun resolve(tile: Tile, item: PlayItem, index: Int): Resolved {
        val label = item.title.ifBlank { "Élément ${index + 1}" }
        return try {
            when (item) {
                is PlayItem.Podcast -> resolvePodcast(tile, item, label, index)
                is PlayItem.Live -> resolveLive(tile, item, label, index)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Resolved.Skipped("« $label » sauté : ${e.toUserMessage()}")
        }
    }

    private suspend fun resolvePodcast(tile: Tile, item: PlayItem.Podcast, label: String, index: Int): Resolved {
        if (item.feedUrl.isBlank()) return Resolved.Skipped("« $label » sauté : aucune adresse de flux")
        val feed = RssParser.fetch(item.feedUrl)
        val episode = feed.episodes.firstOrNull()
            ?: return Resolved.Skipped("« $label » sauté : aucun épisode dans le flux")
        if (item.onlyToday && !isToday(episode.pubDate)) {
            return Resolved.Skipped("« $label » pas encore en ligne")
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(label)
            .setArtist(episode.title)
            .setAlbumTitle(tile.title)
            .setArtworkUri((episode.imageUrl ?: item.imageUrl ?: feed.imageUrl)?.let(Uri::parse))
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()
        return Resolved.Ok(
            MediaItem.Builder()
                .setMediaId("${item.id}#$index")
                .setUri(episode.audioUrl)
                .setMediaMetadata(metadata)
                .build(),
        )
    }

    private suspend fun resolveLive(tile: Tile, item: PlayItem.Live, label: String, index: Int): Resolved {
        if (item.streamUrl.isBlank()) return Resolved.Skipped("« $label » sauté : aucune adresse de flux")
        val url = resolveStreamPlaylist(item.streamUrl.trim())
        val extras = Bundle()
        item.maxMinutes?.takeIf { it > 0 }?.let { extras.putLong(EXTRA_MAX_DURATION_MS, it * 60_000L) }
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
                .setMediaMetadata(metadata)
                .build(),
        )
    }

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

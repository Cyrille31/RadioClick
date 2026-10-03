/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Réponse de l'API iTunes Search. */
@Serializable
data class ITunesResponse(
    val resultCount: Int = 0,
    val results: List<ITunesPodcast> = emptyList(),
)

@Serializable
data class ITunesPodcast(
    val collectionId: Long = 0,
    val collectionName: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl100: String? = null,
    val artworkUrl600: String? = null,
) {
    val name: String get() = collectionName ?: trackName ?: "Sans titre"
    val artwork: String? get() = artworkUrl600 ?: artworkUrl100
}

/** Station renvoyée par l'API Radio Browser. */
@Serializable
data class RadioStation(
    @SerialName("stationuuid") val uuid: String = "",
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val country: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val homepage: String = "",
) {
    val streamUrl: String get() = urlResolved.ifBlank { url }
}

/** Contenu d'un flux RSS de podcast. */
data class Feed(
    val title: String,
    val imageUrl: String?,
    val episodes: List<Episode>,
)

data class Episode(
    val title: String,
    val audioUrl: String,
    /** Date de publication en millisecondes, ou null si absente ou illisible. */
    val pubDate: Long?,
    val imageUrl: String?,
)

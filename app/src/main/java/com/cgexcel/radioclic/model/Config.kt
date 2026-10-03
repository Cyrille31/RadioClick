/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Configuration complète de l'application, enregistrée en JSON. */
@Serializable
data class AppConfig(
    val format: String = FORMAT,
    val version: Int = 1,
    val columns: Int = 2,
    /** Lire les épisodes sans la publicité insérée avant l'émission, quand c'est possible. */
    val skipAds: Boolean = true,
    /** Saut avant / arrière des boutons du lecteur, en secondes. */
    val seekSeconds: Int = 15,
    /** Retour possible dans le passé pendant un direct Radio France, en minutes (0 : désactivé). */
    val liveRewindMinutes: Int = 15,
    val tiles: List<Tile> = emptyList(),
) {
    companion object {
        const val FORMAT = "radioclic-config"
    }
}

/** Une tuile de l'écran principal : un titre, une couleur et une liste de lecture. */
@Serializable
data class Tile(
    val id: String = newId(),
    val title: String = "",
    val color: Long = TilePalette.first(),
    /** Image optionnelle (URL http(s) ou fichier local). Vide : pochette du premier élément. */
    val imageUrl: String? = null,
    val items: List<PlayItem> = emptyList(),
) {
    /** Image affichée : celle de la tuile, sinon la pochette du premier élément qui en a une. */
    val displayImage: String?
        get() = imageUrl?.takeIf { it.isNotBlank() }
            ?: items.firstNotNullOfOrNull { it.imageUrl?.takeIf { url -> url.isNotBlank() } }
}

/** Un élément de liste de lecture. */
@Serializable
sealed class PlayItem {
    abstract val id: String
    abstract val title: String
    abstract val imageUrl: String?

    /**
     * Podcast : le dernier épisode est retrouvé à chaque lecture, via le flux RSS
     * ou, à défaut (émissions Radio France…), via la fiche Apple Podcasts.
     */
    @Serializable
    @SerialName("podcast")
    data class Podcast(
        override val id: String = newId(),
        override val title: String = "",
        val feedUrl: String = "",
        /** Identifiant Apple Podcasts (recherche iTunes) ; null si inconnu. */
        val appleId: Long? = null,
        override val imageUrl: String? = null,
        /** Ne lire le dernier épisode que s'il est paru aujourd'hui, sinon sauter l'élément. */
        val onlyToday: Boolean = false,
    ) : PlayItem() {
        val hasSource: Boolean
            get() = appleId != null || feedUrl.startsWith("http://") || feedUrl.startsWith("https://")
    }

    /** Radio en direct (URL de flux audio). */
    @Serializable
    @SerialName("live")
    data class Live(
        override val id: String = newId(),
        override val title: String = "",
        val streamUrl: String = "",
        override val imageUrl: String? = null,
        /** Durée maximale en minutes avant l'élément suivant ; null : pas de limite. */
        val maxMinutes: Int? = null,
    ) : PlayItem()
}

fun newId(): String = UUID.randomUUID().toString()

/** Palette de couleurs proposée pour les tuiles (ARGB). */
object TilePalette {
    val colors: List<Long> = listOf(
        0xFF1E3A5F, // bleu nuit
        0xFF1565C0, // bleu
        0xFF00838F, // cyan
        0xFF2E7D32, // vert
        0xFF9E9D24, // olive
        0xFFF9A825, // jaune
        0xFFEF6C00, // orange
        0xFFC62828, // rouge
        0xFFAD1457, // framboise
        0xFF6A1B9A, // violet
        0xFF4E342E, // brun
        0xFF455A64, // gris bleu
    )

    fun first(): Long = colors.first()
}

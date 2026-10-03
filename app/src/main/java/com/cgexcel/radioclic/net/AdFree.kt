/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Lecture sans publicité insérée.
 *
 * Les épisodes Radio France passent par « proxycast.radiofrance.fr », qui peut
 * ajouter une publicité avant l'émission puis redirige vers le fichier original
 * sur « media.radiofrance-podcast.net ». On lit directement ce fichier original
 * quand il est disponible ; sinon on garde le lien d'origine.
 */
object AdFree {

    private const val PROXY_HOST = "proxycast.radiofrance.fr"
    private const val MEDIA_BASE = "https://media.radiofrance-podcast.net/podcast09/"

    suspend fun resolve(audioUrl: String): String {
        val direct = directUrl(audioUrl) ?: return audioUrl
        return if (exists(direct)) direct else audioUrl
    }

    /** Adresse du fichier original pour un lien « proxycast », sinon null. */
    fun directUrl(audioUrl: String): String? {
        val url = audioUrl.toHttpUrlOrNull() ?: return null
        if (url.host != PROXY_HOST) return null
        val file = url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return MEDIA_BASE + file
    }

    private suspend fun exists(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

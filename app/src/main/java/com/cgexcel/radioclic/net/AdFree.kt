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

    /**
     * Directs Radio France : les flux « icecast » / « direct » peuvent commencer par une
     * publicité ; le flux HLS officiel (celui du site radiofrance.fr) n'en insère pas.
     */
    suspend fun resolveLive(streamUrl: String): String {
        val hls = liveHlsUrl(streamUrl) ?: return streamUrl
        return if (exists(hls, head = false)) hls else streamUrl
    }

    private val liveFile = Regex("^/(?:live/)?([a-z0-9]+)-(?:hifi|midfi|lofi)\\.(?:aac|mp3)$")

    /** Flux HLS sans publicité équivalent à un direct Radio France, sinon null. */
    fun liveHlsUrl(streamUrl: String): String? {
        val url = streamUrl.toHttpUrlOrNull() ?: return null
        val radioFrance = url.host == "icecast.radiofrance.fr" ||
            (url.host.startsWith("direct.") && url.host.endsWith(".fr") && url.encodedPath.startsWith("/live/"))
        if (!radioFrance) return null
        val station = liveFile.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        return "https://stream.radiofrance.fr/$station/${station}_hifi.m3u8?id=radiofrance"
    }

    private suspend fun exists(url: String, head: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).apply { if (head) head() }.build()
            Http.client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import com.cgexcel.radioclic.model.ITunesPodcast
import com.cgexcel.radioclic.model.ITunesResponse
import com.cgexcel.radioclic.model.RadioStation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

private val apiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Recherche de podcasts via l'API publique iTunes Search (sans clé). */
object PodcastSearch {
    suspend fun search(term: String): List<ITunesPodcast> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("country", "FR")
            .addQueryParameter("limit", "40")
            .addQueryParameter("term", term.trim())
            .build()
        val response = apiJson.decodeFromString(ITunesResponse.serializer(), Http.getText(url.toString()))
        // Les émissions sans flux RSS public (Radio France…) restent jouables via Apple Podcasts.
        return response.results.filter { it.collectionId > 0 || !it.feedUrl.isNullOrBlank() }
    }

    /** Fiche d'un podcast d'après son identifiant Apple ; null si inconnu. */
    suspend fun lookup(appleId: Long): ITunesPodcast? {
        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", appleId.toString())
            .addQueryParameter("country", "FR")
            .build()
        val response = apiJson.decodeFromString(ITunesResponse.serializer(), Http.getText(url.toString()))
        return response.results.firstOrNull { it.collectionId == appleId }
    }
}

/** Recherche de radios via l'API publique Radio Browser (radio-browser.info). */
object RadioSearch {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    private val stopWords = setOf(
        "le", "la", "les", "l", "de", "du", "des", "d", "un", "une", "et", "en", "a", "à", "au", "aux",
        "radio", "direct", "live", "journal", "podcast", "emission", "émission", "info", "infos",
    )

    /**
     * Recherche tolérante : la phrase complète, puis sans les mots vides
     * (« journal France Inter de 8h » → « france inter »), puis par paires de mots.
     */
    suspend fun search(term: String): List<RadioStation> {
        val words = term.lowercase().split(Regex("[\\s'’,;:.!?-]+")).filter { it.isNotBlank() }
        val meaningful = words.filter { it !in stopWords && it.none(Char::isDigit) }
        val candidates = buildList {
            add(term.trim())
            if (meaningful.isNotEmpty()) add(meaningful.joinToString(" "))
            meaningful.windowed(2).forEach { add(it.joinToString(" ")) }
            if (meaningful.size == 1) add(meaningful.first())
        }.distinct().take(5)
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val found = searchExact(candidate)
                if (found.isNotEmpty()) return found
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        return emptyList()
    }

    private suspend fun searchExact(term: String): List<RadioStation> = query("stations/search") {
        addQueryParameter("name", term.trim())
        addQueryParameter("limit", "50")
        addQueryParameter("hidebroken", "true")
        addQueryParameter("order", "clickcount")
        addQueryParameter("reverse", "true")
    }

    /** Stations dont le flux est exactement cette adresse. */
    suspend fun byUrl(streamUrl: String): List<RadioStation> = query("stations/byurl") {
        addQueryParameter("url", streamUrl)
    }

    /** Stations correspondant à un flux : même adresse, sinon même nom. */
    suspend fun related(streamUrl: String, name: String): List<RadioStation> {
        val byUrl = runCatching { byUrl(streamUrl) }.getOrDefault(emptyList())
        val byName = runCatching { searchExact(name) }.getOrDefault(emptyList())
            .filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        return byUrl + byName
    }

    private suspend fun query(
        path: String,
        params: okhttp3.HttpUrl.Builder.() -> Unit,
    ): List<RadioStation> {
        var lastError: Exception = IOException("aucun serveur disponible")
        for (server in servers) {
            try {
                val url = "$server/json/$path".toHttpUrl().newBuilder().apply(params).build()
                val text = Http.getText(url.toString())
                return apiJson.decodeFromString(ListSerializer(RadioStation.serializer()), text)
                    .filter { it.streamUrl.isNotBlank() }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }
}

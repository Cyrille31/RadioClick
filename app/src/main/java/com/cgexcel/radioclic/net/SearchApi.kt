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
        return response.results.filter { !it.feedUrl.isNullOrBlank() }
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

    suspend fun search(term: String): List<RadioStation> {
        var lastError: Exception = IOException("aucun serveur disponible")
        for (server in servers) {
            try {
                val url = "$server/json/stations/search".toHttpUrl().newBuilder()
                    .addQueryParameter("name", term.trim())
                    .addQueryParameter("limit", "50")
                    .addQueryParameter("hidebroken", "true")
                    .addQueryParameter("order", "clickcount")
                    .addQueryParameter("reverse", "true")
                    .build()
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

/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import com.cgexcel.radioclic.model.Episode
import com.cgexcel.radioclic.model.Feed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Derniers épisodes d'une émission d'après sa fiche Apple Podcasts.
 *
 * Certaines émissions (notamment Radio France) ne publient pas leur flux RSS
 * dans l'annuaire iTunes ; la fiche web, elle, contient les épisodes récents
 * (titre, date, lien audio) dans un bloc JSON « serialized-server-data ».
 */
object ApplePodcasts {

    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14)"

    fun pageUrl(appleId: Long) = "https://podcasts.apple.com/fr/podcast/id$appleId"

    suspend fun fetch(appleId: Long): Feed = parse(Http.getText(pageUrl(appleId), BROWSER_UA))

    fun parse(html: String): Feed {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) throw IOException("fiche de l'émission illisible")
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) throw IOException("fiche de l'émission illisible")
        val root = Json.parseToJsonElement(html.substring(start, end))

        val episodes = LinkedHashMap<String, Episode>()
        collect(root, episodes)
        if (episodes.isEmpty()) throw IOException("aucun épisode trouvé")

        val sorted = episodes.values.sortedByDescending { it.pubDate ?: 0L }
        return Feed(
            title = metaContent(html, "og:title")?.substringBefore(" | ")?.trim() ?: "Podcast",
            imageUrl = metaContent(html, "og:image") ?: sorted.firstNotNullOfOrNull { it.imageUrl },
            episodes = sorted,
        )
    }

    /** Parcourt tout le JSON et retient chaque objet « épisode » (titre + date + lien audio). */
    private fun collect(element: JsonElement, out: MutableMap<String, Episode>) {
        when (element) {
            is JsonArray -> element.forEach { collect(it, out) }
            is JsonObject -> {
                val title = element.string("title")
                val date = element.string("releaseDate")
                val audio = element.streamUrl()
                if (title != null && date != null && audio != null && audio !in out) {
                    out[audio] = Episode(
                        title = title,
                        audioUrl = audio,
                        pubDate = RssParser.parseDate(date),
                        imageUrl = element.artwork("episodeArtwork") ?: element.artwork("artwork"),
                    )
                }
                element.values.forEach { collect(it, out) }
            }
            else -> Unit
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.streamUrl(): String? {
        (this["currentMediaEnclosure"] as? JsonObject)?.string("streamUrl")?.let { return it }
        (this["mediaEnclosures"] as? JsonArray)?.forEach { enclosure ->
            (enclosure as? JsonObject)?.string("streamUrl")?.let { return it }
        }
        return null
    }

    private fun JsonObject.artwork(key: String): String? =
        (this[key] as? JsonObject)?.string("template")
            ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")

    private fun metaContent(html: String, property: String): String? =
        Regex("<meta[^>]+property=\"$property\"[^>]+content=\"([^\"]+)\"").find(html)
            ?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"")
}

/** Retrouve les épisodes d'un podcast, quelle que soit sa source. */
object FeedResolver {

    /** Flux RSS Radio France déjà retrouvés, par identifiant Apple. */
    private val radioFranceFeeds = java.util.concurrent.ConcurrentHashMap<Long, String>()

    suspend fun fetch(feedUrl: String, appleId: Long?): Feed {
        if (feedUrl.startsWith("http://") || feedUrl.startsWith("https://")) {
            try {
                val feed = RssParser.fetch(feedUrl)
                if (feed.episodes.isNotEmpty() || appleId == null) return feed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || appleId == null) throw e
            }
        }
        val id = appleId ?: throw IOException("aucune source pour ce podcast")
        radioFranceFeeds[id]?.let { known ->
            fetchRss(known)?.let { return it }
            radioFranceFeeds.remove(id)
        }
        val apple = ApplePodcasts.fetch(id)
        // Apple ne reprend les épisodes Radio France qu'avec retard (souvent plus d'une heure) :
        // le flux RSS officiel, retrouvé d'après le nom des fichiers audio, est à jour bien plus tôt.
        val rss = apple.episodes.firstNotNullOfOrNull { radioFranceFeedUrl(it.audioUrl) } ?: return apple
        val feed = fetchRss(rss) ?: return apple
        radioFranceFeeds[id] = rss
        return feed.copy(
            title = apple.title.takeIf { it != "Podcast" } ?: feed.title,
            imageUrl = feed.imageUrl ?: apple.imageUrl,
        )
    }

    private suspend fun fetchRss(url: String): Feed? = try {
        RssParser.fetch(url).takeIf { it.episodes.isNotEmpty() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private val radioFranceFile = Regex("^(\\d+)-\\d{2}\\.\\d{2}\\.\\d{4}-.+\\.(?:mp3|m4a|aac)$", RegexOption.IGNORE_CASE)

    /**
     * Flux RSS officiel d'une émission Radio France d'après l'adresse d'un de ses épisodes :
     * « …/21003-03.10.2026-ITEMA_….mp3 » → « https://radiofrance-podcast.net/podcast09/rss_21003.xml ».
     */
    fun radioFranceFeedUrl(audioUrl: String): String? {
        val url = audioUrl.toHttpUrlOrNull() ?: return null
        if (!url.host.endsWith("radiofrance.fr") && !url.host.endsWith("radiofrance-podcast.net")) return null
        val file = url.pathSegments.lastOrNull() ?: return null
        val feedId = radioFranceFile.find(file)?.groupValues?.get(1) ?: return null
        return "https://radiofrance-podcast.net/podcast09/rss_$feedId.xml"
    }
}

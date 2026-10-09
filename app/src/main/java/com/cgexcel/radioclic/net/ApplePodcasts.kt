/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import android.content.Context
import android.content.SharedPreferences
import com.cgexcel.radioclic.model.Episode
import com.cgexcel.radioclic.model.Feed
import com.cgexcel.radioclic.model.ITunesPodcast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** Flux RSS Radio France déjà retrouvés, par identifiant Apple (conservés entre les lancements). */
    private var store: SharedPreferences? = null

    fun init(context: Context) {
        store = context.applicationContext.getSharedPreferences("radiofrance_feeds", Context.MODE_PRIVATE)
    }

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
        // Flux déjà connu : une seule requête, légère.
        store?.getString(id.toString(), null)?.let { known ->
            fetchRss(known)?.let { return it }
        }
        val apple = ApplePodcasts.fetch(id)
        // Apple reprend les épisodes Radio France avec retard : le flux RSS officiel de
        // l'émission, retrouvé d'après l'adresse de ses épisodes, est à jour plus tôt.
        val rss = radioFranceFeedUrl(apple.episodes.map { it.audioUrl }) ?: return apple
        val feed = fetchRss(rss)?.takeIf { sameShow(apple, it) } ?: return apple
        store?.edit()?.putString(id.toString(), rss)?.apply()
        return feed
    }

    private suspend fun fetchRss(url: String): Feed? = try {
        RssParser.fetch(url).takeIf { it.episodes.isNotEmpty() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    /**
     * Flux RSS officiel d'une émission Radio France d'après les adresses de ses épisodes :
     * « proxycast.radiofrance.fr/<diffuseur>/<émission>/<épisode>/fichier.mp3 »
     * → « https://radiofrance-podcast.net/podcast09/podcast_<émission>.xml ».
     * L'identifiant d'émission le plus fréquent l'emporte ; null si ce n'est pas Radio France.
     */
    fun radioFranceFeedUrl(audioUrls: List<String>): String? {
        val show = audioUrls.mapNotNull { audio ->
            val url = audio.toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.host != "proxycast.radiofrance.fr") return@mapNotNull null
            url.pathSegments.takeIf { it.size >= 4 }?.get(1)?.takeIf { uuid.matches(it) }?.lowercase()
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return "https://radiofrance-podcast.net/podcast09/podcast_$show.xml"
    }

    /**
     * Comme [fetch], en y ajoutant les épisodes des podcasts « jumeaux » de l'émission :
     * Radio France publie par exemple « L'édito éco » du lundi au jeudi et
     * « L'édito éco VSD » du vendredi au dimanche. Le plus récent des deux est ainsi lu.
     */
    suspend fun fetchWithCompanions(feedUrl: String, appleId: Long?): Feed = coroutineScope {
        val main = async { fetch(feedUrl, appleId) }
        val others = if (appleId == null) emptyList() else companions(appleId).map { id ->
            async { runCatching { fetch("", id) }.getOrNull() }
        }
        val feed = main.await()
        val extra = others.awaitAll().filterNotNull().flatMap { it.episodes }
        val all = feed.episodes + extra
        if (extra.isEmpty() || all.any { it.pubDate == null }) feed
        else feed.copy(episodes = all.sortedByDescending { it.pubDate })
    }

    private const val COMPANIONS_MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /** Identifiants Apple des podcasts jumeaux (mémorisés une semaine). */
    private suspend fun companions(appleId: Long): List<Long> {
        val key = "companions_$appleId"
        val cached = store?.getString(key, null)?.split('|')
        val cachedAt = cached?.getOrNull(0)?.toLongOrNull()
        val cachedIds = cached?.getOrNull(1)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < COMPANIONS_MAX_AGE_MS) return cachedIds
        return try {
            val base = PodcastSearch.lookup(appleId) ?: return cachedIds
            val ids = PodcastSearch.search("${base.name} ${base.artistName.orEmpty()}")
                .filter { isCompanion(base, it) }
                .map { it.collectionId }
                .distinct()
                .take(2)
            store?.edit()?.putString(key, "${System.currentTimeMillis()}|${ids.joinToString(",")}")?.apply()
            ids
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cachedIds
        }
    }

    /**
     * Vrai si [other] est un jumeau de [base] : même éditeur, et un nom égal à celui
     * de l'autre suivi d'un court complément (« L'édito éco » / « L'édito éco VSD »).
     */
    fun isCompanion(base: ITunesPodcast, other: ITunesPodcast): Boolean {
        if (other.collectionId <= 0 || other.collectionId == base.collectionId) return false
        if (norm(base.artistName.orEmpty()) != norm(other.artistName.orEmpty())) return false
        val a = norm(base.name)
        val b = norm(other.name)
        if (a.isEmpty() || b.isEmpty()) return false
        fun extends(short: String, long: String): Boolean =
            long.startsWith("$short ") && long.removePrefix("$short ").split(' ').size <= 2
        return extends(a, b) || extends(b, a)
    }

    private fun norm(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Vérifie que le flux trouvé est bien la même émission (même nom, ou épisodes en commun). */
    fun sameShow(apple: Feed, rss: Feed): Boolean {
        if (norm(apple.title) == norm(rss.title)) return true
        val titles = apple.episodes.map { norm(it.title) }.toSet()
        return rss.episodes.any { norm(it.title) in titles }
    }
}

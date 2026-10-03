/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Recherche d'un logo de station qui s'affiche réellement. Plusieurs sources,
 * dans l'ordre, chaque image étant testée avant d'être retenue :
 * 1. les logos de l'annuaire Radio Browser (par adresse du flux, puis par nom) ;
 * 2. le site web de la station (icône Apple, image de partage, icône du site) ;
 * 3. les pochettes Apple Podcasts publiées par la station.
 */
object Logos {

    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14)"

    suspend fun find(context: Context, streamUrl: String, name: String): String? {
        val stations = RadioSearch.related(streamUrl, name)

        val favicons = stations.map { it.favicon.trim() }.filter { it.isNotBlank() }.distinct()
            .sortedByDescending(::quality)
        firstWorking(context, favicons)?.let { return it }

        val homepages = stations.map { it.homepage.trim() }.filter { it.startsWith("http") }.distinct().take(2)
        for (home in homepages) {
            val fromSite = runCatching { siteImages(home) }.getOrDefault(emptyList())
            firstWorking(context, fromSite)?.let { return it }
        }

        val artworks = runCatching { PodcastSearch.search(name) }.getOrDefault(emptyList())
            .filter { it.artistName?.trim().equals(name.trim(), ignoreCase = true) || it.name.equals(name.trim(), ignoreCase = true) }
            .mapNotNull { it.artwork }
            .take(3)
        return firstWorking(context, artworks)
    }

    /** Vrai si l'image se télécharge et se décode. */
    suspend fun works(context: Context, url: String): Boolean {
        val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
        return context.imageLoader.execute(request) is SuccessResult
    }

    private suspend fun firstWorking(context: Context, urls: List<String>): String? {
        for (url in urls) {
            // Version sécurisée d'abord : beaucoup de logos « http:// » existent aussi en « https:// ».
            val variants = if (url.startsWith("http://")) listOf("https://" + url.removePrefix("http://"), url) else listOf(url)
            for (candidate in variants) {
                if (runCatching { works(context, candidate) }.getOrDefault(false)) return candidate
            }
        }
        return null
    }

    private fun quality(url: String): Int {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".ico") -> 0
            "favicon" in path -> 1
            else -> 2
        }
    }

    /** Images annoncées par la page d'accueil du site de la station, de la plus nette à la plus petite. */
    private suspend fun siteImages(homepage: String): List<String> {
        val base = homepage.toHttpUrlOrNull() ?: return emptyList()
        val html = Http.getText(homepage, BROWSER_UA).take(300_000)
        val tags = Regex("<(?:link|meta)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
        fun attr(tag: String, name: String) =
            Regex("\\b$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        val found = mutableListOf<Pair<Int, String>>()
        for (tag in tags) {
            val rel = attr(tag, "rel")?.lowercase().orEmpty()
            val property = (attr(tag, "property") ?: attr(tag, "name"))?.lowercase().orEmpty()
            val (rank, url) = when {
                "apple-touch-icon" in rel -> 3 to attr(tag, "href")
                property == "og:image" || property == "twitter:image" -> 2 to attr(tag, "content")
                rel.split(' ').contains("icon") -> 1 to attr(tag, "href")
                else -> continue
            }
            val absolute = url?.replace("&amp;", "&")?.let { base.resolve(it)?.toString() } ?: continue
            found += rank to absolute
        }
        return found.sortedByDescending { it.first }.map { it.second }.distinct()
    }
}

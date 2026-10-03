/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import android.util.Xml
import com.cgexcel.radioclic.model.Episode
import com.cgexcel.radioclic.model.Feed
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale

/** Lecture des flux RSS de podcasts. */
object RssParser {

    /** Télécharge et analyse un flux RSS. */
    suspend fun fetch(url: String): Feed {
        val text = Http.getText(url.trim())
        return parse(text)
    }

    fun parse(xml: String): Feed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml.trimStart('﻿', ' ', '\n', '\r', '\t')))

        var feedTitle = ""
        var feedImage: String? = null
        val episodes = mutableListOf<Episode>()

        var inItem = false
        var inChannelImage = false
        var itemTitle = ""
        var itemAudio: String? = null
        var itemDate: Long? = null
        var itemImage: String? = null
        var sawRss = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "rss", "channel" -> sawRss = true
                    "item" -> {
                        inItem = true
                        itemTitle = ""; itemAudio = null; itemDate = null; itemImage = null
                    }
                    "image" -> if (!inItem) inChannelImage = true
                    "itunes:image" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (!href.isNullOrBlank()) {
                            if (inItem) itemImage = href else if (feedImage == null) feedImage = href
                        }
                    }
                    "enclosure" -> if (inItem) {
                        val url = parser.getAttributeValue(null, "url")
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        if (!url.isNullOrBlank() && (itemAudio == null || type.startsWith("audio"))) {
                            itemAudio = url
                        }
                    }
                    "title" -> {
                        val text = parser.safeText()
                        when {
                            inItem -> itemTitle = text
                            !inChannelImage && feedTitle.isEmpty() -> feedTitle = text
                        }
                    }
                    "url" -> if (inChannelImage && !inItem) {
                        val text = parser.safeText()
                        if (text.isNotBlank()) feedImage = feedImage ?: text
                    }
                    "pubDate", "dc:date" -> if (inItem) itemDate = parseDate(parser.safeText())
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "image" -> inChannelImage = false
                    "item" -> {
                        inItem = false
                        itemAudio?.let {
                            episodes += Episode(
                                title = itemTitle.ifBlank { "Épisode" },
                                audioUrl = it,
                                pubDate = itemDate,
                                imageUrl = itemImage,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (!sawRss) throw IOException("ce n'est pas un flux RSS")

        // Le plus récent d'abord (l'ordre du flux est conservé si les dates manquent).
        val sorted = if (episodes.all { it.pubDate != null }) {
            episodes.sortedByDescending { it.pubDate }
        } else {
            episodes
        }
        return Feed(title = feedTitle.ifBlank { "Podcast" }, imageUrl = feedImage, episodes = sorted)
    }

    private fun XmlPullParser.safeText(): String =
        runCatching { nextText() }.getOrDefault("").trim()

    private val datePatterns = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm zzz",
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy",
    )

    /** Analyse une date RFC 822 (pubDate) ou ISO 8601 ; null si illisible. */
    fun parseDate(raw: String): Long? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        for (pattern in datePatterns) {
            val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }
            val date = format.parse(text, ParsePosition(0))
            if (date != null) return date.time
        }
        return null
    }
}

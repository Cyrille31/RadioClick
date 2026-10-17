/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import com.cgexcel.radioclic.model.Feed
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Émission du jour pas encore mise en ligne : reprise dans le direct.
 *
 * Radio France publie le podcast d'une émission avec un délai (parfois plus d'une
 * demi-heure), mais ses directs restent disponibles 24 h. Si l'émission passe
 * chaque jour à heure fixe et qu'elle a déjà été diffusée aujourd'hui, on la
 * reprend dans le direct de la station, de son début à sa fin.
 */
object BroadcastReplay {

    /** Créneau à reprendre : station Radio France et bornes (secondes Unix). */
    data class Plan(val station: String, val startSec: Long, val endSec: Long)

    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    /** Écart toléré entre les heures de diffusion des derniers épisodes. */
    private const val SLOT_TOLERANCE_SEC = 10 * 60

    /** Les directs restent disponibles environ 24 h ; marge de sécurité. */
    private const val MAX_AGE_SEC = 22 * 3600L

    /** Au moins une minute de diffusion avant de reprendre l'émission. */
    private const val MIN_AIRED_SEC = 60L

    /** Petite marge après la durée habituelle (horaires à quelques secondes près). */
    private const val END_MARGIN_SEC = 20L

    private val stations = mapOf(
        "france inter" to "franceinter",
        "franceinfo" to "franceinfo",
        "france info" to "franceinfo",
        "france culture" to "franceculture",
        "france musique" to "francemusique",
        "fip" to "fip",
        "mouv" to "mouv",
    )

    /** Identifiant du direct Radio France d'après l'auteur du flux (« France Inter » → franceinter). */
    fun stationFor(author: String?): String? {
        val key = author?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim() ?: return null
        return stations[key]
    }

    /** Créneau à reprendre aujourd'hui, ou null si l'épisode du jour est en ligne ou si ce n'est pas possible. */
    fun plan(feed: Feed, now: Instant = Instant.now()): Plan? {
        val station = stationFor(feed.author) ?: return null
        val recent = feed.episodes.filter { it.pubDate != null }.sortedByDescending { it.pubDate }.take(10)
        val latest = recent.firstOrNull() ?: return null
        val today = now.atZone(paris)
        val latestAt = Instant.ofEpochMilli(latest.pubDate!!).atZone(paris)
        if (!latestAt.toLocalDate().isBefore(today.toLocalDate())) return null

        // Horaire stable : les derniers épisodes passent tous à la même heure, à quelques minutes près.
        val slot = latestAt.toLocalTime().toSecondOfDay()
        val lastFive = recent.take(5).map { Instant.ofEpochMilli(it.pubDate!!).atZone(paris) }
        if (lastFive.size < 3 || lastFive.any { abs(it.toLocalTime().toSecondOfDay() - slot) > SLOT_TOLERANCE_SEC }) return null

        // L'émission passe ce jour de la semaine (ex. pas le week-end pour une quotidienne en semaine).
        val sameDay = recent.filter {
            Instant.ofEpochMilli(it.pubDate!!).atZone(paris).dayOfWeek == today.dayOfWeek
        }
        if (sameDay.isEmpty()) return null

        // Durée habituelle ce jour de la semaine (ex. journal plus court le week-end), sinon tous jours confondus.
        val durations = (sameDay.mapNotNull { it.durationSec }.filter { it > 0 }
            .ifEmpty { recent.mapNotNull { it.durationSec }.filter { it > 0 } }).sorted()
        if (durations.isEmpty()) return null
        val duration = durations[durations.size / 2]

        val start: ZonedDateTime = today.toLocalDate().atStartOfDay(paris).plusSeconds(slot.toLong())
        val startSec = start.toEpochSecond()
        val nowSec = now.epochSecond
        if (nowSec - startSec < MIN_AIRED_SEC || nowSec - startSec > MAX_AGE_SEC) return null
        return Plan(station, startSec, startSec + duration + END_MARGIN_SEC)
    }
}

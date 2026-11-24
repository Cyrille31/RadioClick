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

    /** Résultat de l'analyse : créneau à reprendre, ou raison pour laquelle on ne le fait pas. */
    sealed class Decision {
        /** Épisode du jour en ligne, ou rien à reprendre : comportement habituel, sans message. */
        data object NotNeeded : Decision()
        /** L'épisode du jour manque, mais l'émission n'est pas encore passée (heure de diffusion). */
        data class NotYetAired(val startSec: Long) : Decision()
        /** L'épisode du jour manque et la reprise est impossible, pour la raison donnée. */
        data class Impossible(val reason: String) : Decision()
        data class Replay(val plan: Plan) : Decision()
    }

    /** Durée supposée quand le flux n'en donne aucune. */
    private const val DEFAULT_DURATION_SEC = 30 * 60

    /** Créneau à reprendre aujourd'hui, ou null (voir [decide]). */
    fun plan(feed: Feed, now: Instant = Instant.now(), stationHint: String? = null): Plan? =
        (decide(feed, now, stationHint) as? Decision.Replay)?.plan

    /** Heure de diffusion écrite dans le nom de l'émission (« Journal de 08h00 », « Le journal de 13h »). */
    fun slotFromTitle(title: String): Int? {
        val match = Regex("(?<![\\p{L}\\p{N}])(\\d{1,2})\\s*h\\s*(\\d{2})?(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .find(title) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        return hour * 3600 + minute * 60
    }

    /**
     * Décide s'il faut reprendre l'émission du jour dans le direct.
     * [stationHint] : éditeur de l'émission connu par ailleurs (annuaire Apple), si le flux ne le donne pas.
     */
    fun decide(feed: Feed, now: Instant = Instant.now(), stationHint: String? = null): Decision {
        val recent = feed.episodes.filter { it.pubDate != null }.sortedByDescending { it.pubDate }.take(10)
        val latest = recent.firstOrNull() ?: return Decision.NotNeeded
        val today = now.atZone(paris)
        val latestAt = Instant.ofEpochMilli(latest.pubDate!!).atZone(paris)
        if (!latestAt.toLocalDate().isBefore(today.toLocalDate())) return Decision.NotNeeded

        // Pas une émission de direct Radio France, ou pas de jour fixe : rien à reprendre.
        val station = stationFor(feed.author) ?: stationFor(stationHint) ?: return Decision.NotNeeded
        val sameDay = recent.filter {
            Instant.ofEpochMilli(it.pubDate!!).atZone(paris).dayOfWeek == today.dayOfWeek
        }
        if (sameDay.isEmpty()) return Decision.NotNeeded

        // Heure de diffusion : celle écrite dans le nom de l'émission, sinon l'heure habituelle
        // de mise en ligne des derniers épisodes (au moins 3 sur 5 au même horaire).
        val times = recent.take(5).map { Instant.ofEpochMilli(it.pubDate!!).atZone(paris).toLocalTime().toSecondOfDay() }
        val usual = times.sorted()[times.size / 2]
        val fromTitle = slotFromTitle(feed.title)
            // Les épisodes paraissent à cette heure ou un peu après (publication différée).
            ?.takeIf { slot -> times.count { it in (slot - SLOT_TOLERANCE_SEC)..(slot + 3 * 3600) } >= (times.size + 1) / 2 }
        val slot = when {
            // Mise en ligne habituelle à l'heure annoncée : elle est la plus précise (08:00:01…).
            fromTitle != null && abs(usual - fromTitle) <= SLOT_TOLERANCE_SEC -> usual
            // Mise en ligne différée : on reprend à l'heure annoncée dans le nom.
            fromTitle != null -> fromTitle
            times.size >= 3 && times.count { abs(it - usual) <= SLOT_TOLERANCE_SEC } >= 3 -> usual
            // Pas d'heure de diffusion fixe (série publiée en podcast) : rien à reprendre.
            else -> return Decision.NotNeeded
        }

        // Durée habituelle ce jour de la semaine (ex. journal plus court le week-end), sinon tous jours confondus.
        val durations = (sameDay.mapNotNull { it.durationSec }.filter { it > 0 }
            .ifEmpty { recent.mapNotNull { it.durationSec }.filter { it > 0 } }).sorted()
        val duration = if (durations.isEmpty()) DEFAULT_DURATION_SEC else durations[durations.size / 2]

        val start: ZonedDateTime = today.toLocalDate().atStartOfDay(paris).plusSeconds(slot.toLong())
        val startSec = start.toEpochSecond()
        val nowSec = now.epochSecond
        if (nowSec - startSec < MIN_AIRED_SEC) return Decision.NotYetAired(startSec)
        if (nowSec - startSec > MAX_AGE_SEC) return Decision.Impossible("diffusée il y a trop longtemps")
        return Decision.Replay(Plan(station, startSec, startSec + duration + END_MARGIN_SEC))
    }
}

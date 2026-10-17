/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.cgexcel.radioclic.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Retour en arrière dans les directs Radio France.
 *
 * Le flux HLS officiel n'annonce que les ~30 dernières secondes, mais les
 * segments plus anciens (numérotés régulièrement) restent disponibles sur le
 * serveur. On fabrique donc, à chaque rechargement, une liste de lecture qui
 * remonte de N minutes : le lecteur peut alors reculer dans le direct.
 */
object LiveTimeshift {

    private const val SCHEME = "rclive"
    private const val BACK_PARAM = "rcback"
    private const val REPLAY_SCHEME = "rcreplay"
    private const val FROM_PARAM = "rcfrom"
    private const val TO_PARAM = "rcto"
    private const val LEAD_PARAM = "rclead"

    /** Avance gardée avant le début d'un créneau, pour pouvoir reculer si l'émission a commencé plus tôt. */
    const val REPLAY_LEAD_SEC = 5 * 60L

    /** Vrai pour une liste HLS de direct Radio France. */
    fun isRadioFranceHls(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.host == "stream.radiofrance.fr" && u.encodedPath.endsWith(".m3u8")
    }

    /** Adresse interne lue par [Source], qui étend la fenêtre du direct de [minutes]. */
    fun wrap(hlsUrl: String, minutes: Int): String =
        Uri.parse(hlsUrl).buildUpon().scheme(SCHEME).appendQueryParameter(BACK_PARAM, minutes.toString()).build().toString()

    /** Adresse interne d'un créneau passé du direct [station] (bornes en secondes Unix). */
    fun replayUrl(station: String, fromSec: Long, toSec: Long, leadSec: Long = REPLAY_LEAD_SEC): String =
        "$REPLAY_SCHEME://stream.radiofrance.fr/$station/${station}_hifi.m3u8?id=radiofrance" +
            "&$FROM_PARAM=$fromSec&$TO_PARAM=$toSec&$LEAD_PARAM=$leadSec"

    /**
     * Liste de lecture du créneau [fromSec, toSec[ d'après la liste du direct : les segments
     * sont numérotés régulièrement et nommés d'après leur heure de début (secondes Unix).
     * Créneau terminé : liste complète (VOD) ; sinon liste qui s'allonge (EVENT).
     * La liste commence [leadSec] secondes plus tôt, mais la lecture démarre à [fromSec] :
     * on peut ainsi reculer avant le début prévu.
     */
    fun replay(playlist: String, playlistUrl: String, fromSec: Long, toSec: Long, leadSec: Long = 0): String {
        val base = playlistUrl.toHttpUrlOrNull() ?: throw IOException("adresse invalide")
        val lines = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lastUri = lines.lastOrNull { !it.startsWith("#") } ?: throw IOException("direct vide")
        val match = segmentName.find(absolute(base, lastUri)) ?: throw IOException("direct illisible")
        val duration = lines.firstOrNull { it.startsWith("#EXTINF:") }
            ?.substringAfter(':')?.substringBefore(',')?.toDoubleOrNull()
            ?.takeIf { it > 0 } ?: throw IOException("direct illisible")
        val (prefix, seqText, stampText, suffix) = match.destructured
        val lastSeq = seqText.toLong()
        val lastStamp = stampText.toLong()
        val step = duration.roundToLong().coerceAtLeast(1)
        if (fromSec > lastStamp) throw IOException("émission pas encore diffusée")

        // Premier segment : celui qui contient le début de l'avance (fromSec - leadSec).
        val back = (lastStamp - (fromSec - leadSec.coerceAtLeast(0)) + step - 1) / step
        val firstSeq = lastSeq - back
        val firstStamp = lastStamp - back * step
        val complete = lastStamp + step >= toSec
        val extinf = "#EXTINF:" + String.format(java.util.Locale.US, "%.3f", duration) + ","
        val out = buildList {
            add("#EXTM3U")
            add("#EXT-X-VERSION:3")
            add("#EXT-X-TARGETDURATION:" + kotlin.math.ceil(duration).toLong())
            add("#EXT-X-MEDIA-SEQUENCE:$firstSeq")
            add("#EXT-X-PLAYLIST-TYPE:" + if (complete) "VOD" else "EVENT")
            add("#EXT-X-START:TIME-OFFSET=${fromSec - firstStamp},PRECISE=YES")
            var seq = firstSeq
            var stamp = firstStamp
            while (seq <= lastSeq && stamp < toSec) {
                add("#EXT-X-PROGRAM-DATE-TIME:" + DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(stamp)))
                add(extinf)
                add("$prefix${seq}_$stamp$suffix")
                seq++
                stamp += step
            }
            if (complete) add("#EXT-X-ENDLIST")
        }
        return out.joinToString("\n", postfix = "\n")
    }

    private val segmentName = Regex("^(.*_)(\\d+)_(\\d+)(\\.(?:ts|aac|mp4|m4s)(?:\\?.*)?)$")

    /**
     * Ajoute [backSeconds] secondes de segments plus anciens au début de la liste.
     * Les adresses relatives sont rendues absolues (base [playlistUrl]).
     */
    fun extend(playlist: String, playlistUrl: String, backSeconds: Int): String {
        val base = playlistUrl.toHttpUrlOrNull() ?: return playlist
        val lines = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val header = mutableListOf<String>()
        var mediaSequence: Long? = null
        val body = mutableListOf<String>()
        var inBody = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSequence = line.substringAfter(':').toLongOrNull()
                line.startsWith("#EXT-X-START") -> Unit // le lecteur démarre près du direct
                !inBody && (line.startsWith("#EXTINF") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")) -> {
                    inBody = true
                    body += line
                }
                inBody -> body += if (line.startsWith("#")) line else absolute(base, line)
                else -> header += line
            }
        }
        val firstUri = body.firstOrNull { !it.startsWith("#") }
        val match = firstUri?.let { segmentName.find(it) }
        val duration = body.firstOrNull { it.startsWith("#EXTINF:") }
            ?.substringAfter(':')?.substringBefore(',')?.toDoubleOrNull()
        val firstDate = body.takeWhile { !it.startsWith("#EXTINF") }
            .firstOrNull { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }
            ?.substringAfter(':')?.let { runCatching { Instant.parse(it) }.getOrNull() }

        if (mediaSequence == null || match == null || duration == null || duration <= 0 || backSeconds <= 0) {
            return (header + listOfNotNull(mediaSequence?.let { "#EXT-X-MEDIA-SEQUENCE:$it" }) + body)
                .joinToString("\n", postfix = "\n")
        }
        val (prefix, seqText, stampText, suffix) = match.destructured
        val seq = seqText.toLong()
        val stamp = stampText.toLong()
        val step = duration.roundToLong().coerceAtLeast(1)
        val count = (backSeconds / duration).toInt()
        val extinf = body.first { it.startsWith("#EXTINF:") }

        val older = buildList {
            for (k in count downTo 1) {
                firstDate?.let { add("#EXT-X-PROGRAM-DATE-TIME:" + DateTimeFormatter.ISO_INSTANT.format(it.minusMillis((duration * 1000 * k).roundToLong()))) }
                add(extinf)
                add("$prefix${seq - k}_${stamp - step * k}$suffix")
            }
        }
        return (header + "#EXT-X-MEDIA-SEQUENCE:${mediaSequence - count}" + older + body)
            .joinToString("\n", postfix = "\n")
    }

    private fun absolute(base: okhttp3.HttpUrl, uri: String): String = base.resolve(uri)?.toString() ?: uri

    /** Source de données : sert la liste étendue pour les adresses « rclive », délègue le reste. */
    @OptIn(UnstableApi::class)
    class Source(private val upstream: DataSource) : DataSource {
        private var data: ByteArray? = null
        private var position = 0
        private var uri: Uri? = null
        private var delegating = false

        override fun addTransferListener(transferListener: TransferListener) =
            upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            if (dataSpec.uri.scheme == REPLAY_SCHEME) return openReplay(dataSpec)
            if (dataSpec.uri.scheme != SCHEME) {
                delegating = true
                return upstream.open(dataSpec)
            }
            delegating = false
            val minutes = dataSpec.uri.getQueryParameter(BACK_PARAM)?.toIntOrNull() ?: 0
            val real = dataSpec.uri.buildUpon().scheme("https").clearQuery().apply {
                dataSpec.uri.queryParameterNames.filter { it != BACK_PARAM }.forEach { name ->
                    dataSpec.uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) }
                }
            }.build().toString()
            val text = Http.client.newCall(Request.Builder().url(real).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Erreur HTTP ${response.code}")
                response.body?.string() ?: throw IOException("Réponse vide")
            }
            val bytes = extend(text, real, minutes * 60).toByteArray(Charsets.UTF_8)
            data = bytes
            position = min(dataSpec.position.toInt(), bytes.size)
            return (bytes.size - position).toLong()
        }

        private fun openReplay(dataSpec: DataSpec): Long {
            delegating = false
            val from = dataSpec.uri.getQueryParameter(FROM_PARAM)?.toLongOrNull() ?: throw IOException("créneau invalide")
            val to = dataSpec.uri.getQueryParameter(TO_PARAM)?.toLongOrNull() ?: throw IOException("créneau invalide")
            val lead = dataSpec.uri.getQueryParameter(LEAD_PARAM)?.toLongOrNull() ?: 0L
            val real = dataSpec.uri.buildUpon().scheme("https").clearQuery()
                .appendQueryParameter("id", "radiofrance").build().toString()
            val text = Http.client.newCall(Request.Builder().url(real).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Erreur HTTP ${response.code}")
                response.body?.string() ?: throw IOException("Réponse vide")
            }
            val bytes = replay(text, real, from, to, lead).toByteArray(Charsets.UTF_8)
            data = bytes
            position = min(dataSpec.position.toInt(), bytes.size)
            return (bytes.size - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delegating) return upstream.read(buffer, offset, length)
            val bytes = data ?: return C.RESULT_END_OF_INPUT
            if (length == 0) return 0
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = min(length, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (delegating) upstream.responseHeaders else emptyMap()

        override fun close() {
            if (delegating) upstream.close()
            delegating = false
            data = null
        }

        class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
            override fun createDataSource(): DataSource = Source(upstream.createDataSource())
        }
    }
}

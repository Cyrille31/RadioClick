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

    /** Vrai pour une liste HLS de direct Radio France. */
    fun isRadioFranceHls(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.host == "stream.radiofrance.fr" && u.encodedPath.endsWith(".m3u8")
    }

    /** Adresse interne lue par [Source], qui étend la fenêtre du direct de [minutes]. */
    fun wrap(hlsUrl: String, minutes: Int): String =
        Uri.parse(hlsUrl).buildUpon().scheme(SCHEME).appendQueryParameter(BACK_PARAM, minutes.toString()).build().toString()

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

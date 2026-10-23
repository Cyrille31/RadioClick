/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import android.net.Uri
import com.cgexcel.radioclic.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Retour en arrière dans les directs ordinaires (radios hors Radio France).
 *
 * Ces flux (MP3 / AAC continus) ne permettent pas de reculer. Une seule connexion
 * les reçoit : chaque bloc reçu part aussitôt vers le lecteur (adresse « stream »,
 * démarrage immédiat comme une radio ordinaire) et est en même temps enregistré en
 * segments de 2 s dans le cache de l'application, en ne gardant que les dernières
 * minutes. Pour reculer, le service bascule une fois sur la liste HLS locale de
 * cet enregistrement (« playlist.m3u8 ») : on peut alors aller jusqu'au début de
 * l'enregistrement, sans jamais dépasser le direct.
 *
 * Un seul enregistrement à la fois ; il est arrêté et ses fichiers supprimés
 * dès que la radio n'est plus lue (arrêt, élément suivant, fin du service), et
 * les restes d'un arrêt brutal sont effacés au démarrage suivant.
 */
object LiveBuffer {

    const val SCHEME = "rcbuf"
    private const val SEGMENT_SEC = 2.0
    /** Segments disponibles avant de commencer la lecture. */
    private const val MIN_SEGMENTS = 3
    /** Plafond d'espace disque, quelle que soit la durée demandée. */
    private const val MAX_BYTES = 80L * 1024 * 1024
    /** Mémoire maximale en attente de lecture (lecteur en pause sur le direct). */
    private const val MAX_TEE_BYTES = 8L * 1024 * 1024

    private var root: File? = null

    // Numéros de segments et horloge audio croissants d'un enregistrement à l'autre : après un
    // redémarrage (longue pause, coupure), le lecteur voit une suite, pas une liste « remise à zéro ».
    private val nextSeq = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var audioClockSec = 0.0
    private var current: Recording? = null
    private val lock = Any()

    /** Client sans limite de durée totale : un direct dure aussi longtemps qu'on l'écoute. */
    private val streamClient: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** Dossier de travail ; supprime ce qu'un arrêt brutal aurait laissé. */
    fun init(cacheDir: File) {
        val dir = File(cacheDir, "livebuffer")
        dir.deleteRecursively()
        root = dir
    }

    /** Format du flux (« mp3 » ou « aac ») s'il peut être enregistré, sinon null. */
    suspend fun probe(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            streamClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val input = response.body?.byteStream() ?: return@use null
                val buf = ByteArray(32 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                    AudioFrames.detect(buf, n)?.let { return@use it }
                }
                null
            }
        }.getOrNull()
    }

    /** Adresse de la liste de lecture locale d'un flux enregistré (pour reculer). */
    fun playlistUrl(streamUrl: String, minutes: Int): String = localUrl("playlist.m3u8", streamUrl, minutes)

    /** Adresse du flux en temps réel, lu au fil de l'eau pendant l'enregistrement. */
    fun teeUrl(streamUrl: String, minutes: Int): String = localUrl("stream", streamUrl, minutes)

    private fun localUrl(path: String, streamUrl: String, minutes: Int): String =
        Uri.Builder().scheme(SCHEME).authority("live").path(path)
            .appendQueryParameter("src", streamUrl)
            .appendQueryParameter("min", minutes.toString())
            .build().toString()

    /** Vrai si l'adresse est une liste, un segment ou le flux en temps réel de [LiveBuffer]. */
    fun handles(uri: Uri): Boolean = uri.scheme == SCHEME

    /** Vrai pour le flux en temps réel (lecture au fil de l'eau). */
    fun isTee(uri: Uri): Boolean = uri.scheme == SCHEME && uri.path.orEmpty().endsWith("/stream")

    /** Ouvre le flux en temps réel ; l'enregistrement démarre s'il ne tourne pas déjà. */
    fun openTee(uri: Uri): Tee {
        val src = uri.getQueryParameter("src") ?: throw IOException("flux inconnu")
        val minutes = uri.getQueryParameter("min")?.toIntOrNull()?.coerceAtLeast(1) ?: 15
        return synchronized(lock) {
            val existing = current?.takeIf { it.src == src && it.isAlive }
            val rec = existing ?: run {
                current?.stop()
                val dir = root ?: throw IOException("enregistrement indisponible")
                Recording(src, minutes * 60.0, dir).also { current = it }
            }
            // Abonnement avant le démarrage : les premières secondes envoyées par le serveur sont lues aussi.
            rec.subscribe().also { if (existing == null) rec.start() }
        }
    }

    /** Contenu demandé par le lecteur (liste de lecture ou segment) ; appelé hors du fil principal. */
    fun open(uri: Uri): ByteArray {
        val path = uri.path.orEmpty()
        return if (path.endsWith("playlist.m3u8")) {
            val src = uri.getQueryParameter("src") ?: throw IOException("flux inconnu")
            val minutes = uri.getQueryParameter("min")?.toIntOrNull()?.coerceAtLeast(1) ?: 15
            recordingFor(src, minutes).playlist()
        } else {
            // « /seg/<enregistrement>/<numéro>.<ext> »
            val parts = path.trim('/').split('/')
            val rec = synchronized(lock) { current }?.takeIf { parts.getOrNull(1) == it.id }
                ?: throw IOException("enregistrement terminé")
            val seq = parts.getOrNull(2)?.substringBefore('.')?.toLongOrNull() ?: throw IOException("segment inconnu")
            rec.segment(seq)
        }
    }

    /** Arrête l'enregistrement en cours et supprime ses fichiers. */
    fun stop() {
        val rec = synchronized(lock) { current.also { current = null } }
        rec?.stop()
    }

    /** Vrai si un enregistrement est en cours. */
    val isRecording: Boolean get() = synchronized(lock) { current != null }

    private fun recordingFor(src: String, minutes: Int): Recording {
        val rec = synchronized(lock) {
            current?.takeIf { it.src == src && it.isAlive } ?: run {
                current?.stop()
                val dir = root ?: throw IOException("enregistrement indisponible")
                Recording(src, minutes * 60.0, dir).also { current = it; it.start() }
            }
        }
        rec.awaitReady()
        return rec
    }

    /**
     * Lecture au fil de l'eau : les blocs reçus du réseau, dans l'ordre. En mémoire seulement,
     * libérée à la fermeture ; au-delà de [MAX_TEE_BYTES] en attente (longue pause), la lecture
     * s'interrompt plutôt que de remplir la mémoire.
     */
    class Tee internal constructor(private val onClose: (Tee) -> Unit) {
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private val queued = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var finished = false
        @Volatile private var overflow = false
        @Volatile private var error: IOException? = null
        private var chunk: ByteArray? = null
        private var pos = 0

        internal fun offer(data: ByteArray, count: Int) {
            if (finished || overflow) return
            if (queued.get() + count > MAX_TEE_BYTES) {
                overflow = true
                queue.clear()
                queued.set(0)
                queue.offer(ByteArray(0))
                return
            }
            queued.addAndGet(count.toLong())
            queue.offer(data.copyOf(count))
        }

        internal fun finish() {
            finished = true
            queue.offer(ByteArray(0)) // réveille le lecteur
        }

        internal fun fail(e: IOException) {
            error = e
            finish()
        }

        /** Comme InputStream.read ; -1 en fin de flux (enregistrement arrêté). Bloquant. */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                chunk?.let { c ->
                    if (pos < c.size) {
                        val n = minOf(length, c.size - pos)
                        System.arraycopy(c, pos, buffer, offset, n)
                        pos += n
                        return n
                    }
                    chunk = null
                }
                if (overflow) throw IOException("lecture interrompue (pause trop longue)")
                error?.let { throw it }
                if (finished && queue.isEmpty()) return -1
                val next = queue.poll(30, TimeUnit.SECONDS) ?: throw IOException("délai dépassé")
                if (next.isEmpty()) continue
                queued.addAndGet(-next.size.toLong())
                chunk = next
                pos = 0
            }
        }

        fun close() {
            finished = true
            queue.clear()
            chunk = null
            onClose(this)
        }
    }

    private class Segment(val seq: Long, val file: File, val durationSec: Double, val discontinuity: Boolean)

    private class Recording(val src: String, val windowSec: Double, root: File) {
        val id = System.nanoTime().toString(36)
        private val dir = File(root, id)
        private val segments = ArrayDeque<Segment>()
        private var totalBytes = 0L
        /** Ruptures sorties de la fenêtre (EXT-X-DISCONTINUITY-SEQUENCE). */
        private var discontinuitySeq = 0L
        @Volatile private var stopped = false
        @Volatile private var failure: IOException? = null
        @Volatile private var discontinuity = false
        private var call: okhttp3.Call? = null
        private val tees = java.util.concurrent.CopyOnWriteArrayList<Tee>()

        fun subscribe(): Tee = Tee { tees.remove(it) }.also { tees.add(it) }
        private val thread = Thread({ run() }, "RadioClic-LiveBuffer")

        val isAlive: Boolean get() = !stopped && failure == null

        fun start() {
            dir.mkdirs()
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            stopped = true
            tees.forEach { it.finish() }
            tees.clear()
            synchronized(this) { call }?.cancel()
            synchronized(segments) { segments.clear() }
            dir.deleteRecursively()
        }

        /** Attend quelques segments avant de laisser le lecteur démarrer. */
        fun awaitReady() {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                failure?.let { throw it }
                if (stopped) throw IOException("enregistrement arrêté")
                if (synchronized(segments) { segments.size } >= MIN_SEGMENTS) return
                Thread.sleep(100)
            }
            throw IOException("délai dépassé")
        }

        private fun run() {
            var attempts = 0
            while (!stopped) {
                try {
                    val newCall = streamClient.newCall(Request.Builder().url(src).build())
                    synchronized(this) { call = newCall }
                    newCall.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Erreur HTTP ${response.code}")
                        val input = response.body?.byteStream() ?: throw IOException("Réponse vide")
                        val splitter = AudioFrames.Splitter(SEGMENT_SEC) { bytes, sec, f -> add(bytes, sec, f) }
                        val buf = ByteArray(16 * 1024)
                        attempts = 0
                        while (!stopped) {
                            val n = input.read(buf)
                            if (n < 0) break
                            // Une seule réception : vers le lecteur au fil de l'eau, et vers l'enregistrement.
                            tees.forEach { it.offer(buf, n) }
                            splitter.feed(buf, n)
                        }
                    }
                } catch (e: IOException) {
                    if (stopped) return
                    if (++attempts > 3 && synchronized(segments) { segments.isEmpty() }) {
                        failure = e
                        tees.forEach { it.fail(e) }
                        return
                    }
                }
                if (stopped) return
                // Coupure : on se reconnecte ; le lecteur est prévenu de la rupture.
                discontinuity = true
                Thread.sleep(2_000)
            }
        }

        private fun add(bytes: ByteArray, sec: Double, f: String) {
            if (stopped) return
            val seq = nextSeq.getAndIncrement()
            val file = File(dir, "$seq.$f")
            // Horodatage ID3 attendu en tête des segments audio HLS.
            file.writeBytes(AudioFrames.id3Timestamp(audioClockSec) + bytes)
            audioClockSec += sec
            synchronized(segments) {
                segments.addLast(Segment(seq, file, sec, discontinuity))
                discontinuity = false
                totalBytes += file.length()
                // Fenêtre glissante : durée demandée, et plafond d'espace disque.
                while (segments.size > MIN_SEGMENTS &&
                    (segments.sumOf { it.durationSec } > windowSec || totalBytes > MAX_BYTES)
                ) {
                    val old = segments.removeFirst()
                    if (old.discontinuity) discontinuitySeq++
                    totalBytes -= old.file.length()
                    old.file.delete()
                }
            }
        }

        fun playlist(): ByteArray {
            val (snapshot, discontinuities) = synchronized(segments) { segments.toList() to discontinuitySeq }
            if (snapshot.isEmpty()) throw IOException("enregistrement vide")
            val target = ceil(snapshot.maxOf { it.durationSec }).toInt()
            val text = buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n")
                append("#EXT-X-TARGETDURATION:$target\n")
                append("#EXT-X-MEDIA-SEQUENCE:${snapshot.first().seq}\n")
                append("#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuities\n")
                for (s in snapshot) {
                    if (s.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                    append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationSec)).append(",\n")
                    append("$SCHEME://live/seg/$id/${s.file.name}\n")
                }
            }
            return text.toByteArray(Charsets.UTF_8)
        }

        fun segment(seq: Long): ByteArray {
            val s = synchronized(segments) { segments.firstOrNull { it.seq == seq } }
                ?: throw IOException("segment expiré")
            return s.file.readBytes()
        }
    }
}

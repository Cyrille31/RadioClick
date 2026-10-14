/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.cgexcel.radioclic.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/**
 * Retour en arrière dans n'importe quel direct MP3 / AAC.
 *
 * Pendant l'écoute, le flux est enregistré au fur et à mesure dans un fichier
 * circulaire (les N dernières minutes). Le lecteur lit ce fichier plutôt que le
 * réseau : reculer, avancer jusqu'au direct ou reprendre après une pause revient
 * simplement à relire le fichier à un autre endroit.
 */
class LiveBuffer private constructor(
    val source: String,
    private val file: File,
    private val minutes: Int,
) {
    private val lock = Object()
    private var raf: RandomAccessFile? = null
    private var capacity = 0L

    /** Nombre total d'octets reçus depuis le début de l'enregistrement. */
    @Volatile var written = 0L
        private set
    @Volatile private var failure: IOException? = null
    @Volatile private var stopped = false
    private var declaredBytesPerSecond = 0L
    private var firstByteAt = 0L
    private val thread = Thread(::record, "RadioClic-direct").apply { isDaemon = true }

    /** Position (octets absolus) du début de la lecture de l'élément en cours. */
    @Volatile var playStart = 0L

    /** Plus ancien octet encore disponible. */
    val oldest: Long get() = max(0L, written - capacity)

    /** Débit du flux en octets par seconde (annoncé, sinon mesuré, sinon 128 kb/s). */
    fun bytesPerSecond(): Long {
        if (declaredBytesPerSecond > 0) return declaredBytesPerSecond
        val elapsed = System.currentTimeMillis() - firstByteAt
        if (firstByteAt > 0 && elapsed > 4_000 && written > 0) return max(1L, written * 1000 / elapsed)
        return 16_000L
    }

    /** Octet absolu en cours d'écoute, d'après la position du lecteur dans l'élément. */
    fun playbackPosition(playerPositionMs: Long): Long =
        (playStart + playerPositionMs * bytesPerSecond() / 1000).coerceIn(oldest, written)

    private fun record() {
        var delayMs = 1_000L
        var everConnected = false
        val startedAt = System.currentTimeMillis()
        while (!stopped) {
            try {
                // Pas d'en-tête « Icy-MetaData » : le serveur envoie l'audio seul.
                val call = Http.client.newBuilder().readTimeout(java.time.Duration.ofSeconds(20)).callTimeout(java.time.Duration.ZERO).build()
                    .newCall(Request.Builder().url(source).build())
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Erreur HTTP ${response.code}")
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    if (!isSupported(type)) {
                        failure = UnsupportedFormat(type)
                        stopped = true
                        return@use
                    }
                    if (!everConnected) {
                        everConnected = true
                        val kbps = response.header("icy-br")?.substringBefore(',')?.trim()?.toLongOrNull() ?: 0
                        declaredBytesPerSecond = kbps * 125
                        val maxRate = if (declaredBytesPerSecond > 0) declaredBytesPerSecond * 6 / 5 else 40_000L
                        open(min(256L * 1024 * 1024, max(4L * 1024 * 1024, maxRate * minutes * 60)))
                    }
                    delayMs = 1_000L
                    val input = response.body?.byteStream() ?: throw IOException("flux vide")
                    val chunk = ByteArray(16 * 1024)
                    while (!stopped) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (n > 0) append(chunk, n)
                    }
                }
            } catch (e: IOException) {
                if (!everConnected && System.currentTimeMillis() - startedAt > 20_000) {
                    failure = e
                    stopped = true
                }
            }
            if (!stopped) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    break
                }
                delayMs = min(delayMs * 2, 10_000L)
            }
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun open(bytes: Long) = synchronized(lock) {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw").apply { setLength(0) }
        capacity = bytes
    }

    private fun append(data: ByteArray, length: Int) = synchronized(lock) {
        val f = raf ?: return
        if (firstByteAt == 0L) firstByteAt = System.currentTimeMillis()
        var offset = 0
        while (offset < length) {
            val at = (written % capacity)
            val n = min((length - offset).toLong(), capacity - at).toInt()
            f.seek(at)
            f.write(data, offset, n)
            offset += n
            written += n
        }
        lock.notifyAll()
    }

    /** Lit à partir de l'octet absolu [position] ; attend les données à la pointe du direct. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Pair<Long, Int> {
        synchronized(lock) {
            while (true) {
                failure?.let { throw it }
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val f = raf
                val start = max(position, oldest) // données écrasées : on reprend au plus ancien
                if (f != null && start < written) {
                    val at = start % capacity
                    val n = min(min(length.toLong(), written - start), capacity - at).toInt()
                    f.seek(at)
                    f.readFully(buffer, offset, n)
                    return start to n
                }
                if (stopped) throw IOException("direct interrompu")
                try {
                    lock.wait(250)
                } catch (_: InterruptedException) {
                    throw InterruptedIOException()
                }
            }
        }
    }

    fun release() {
        stopped = true
        thread.interrupt()
        synchronized(lock) {
            runCatching { raf?.close() }
            raf = null
            lock.notifyAll()
        }
        file.delete()
    }

    /** Format non lisible depuis le milieu du flux (Ogg, HLS…) : on lira le direct sans tampon. */
    class UnsupportedFormat(type: String) : IOException("format de direct non enregistrable : $type")

    companion object {
        private const val SCHEME = "rcbuf"
        private val buffers = mutableMapOf<String, LiveBuffer>()
        @Volatile private var cacheDir: File? = null

        fun init(context: Context) {
            cacheDir = File(context.cacheDir, "direct")
        }

        private fun isSupported(type: String): Boolean =
            type.startsWith("audio/mpeg") || type.startsWith("audio/mp3") || type.startsWith("audio/aac") ||
                type.startsWith("audio/aacp") || type.startsWith("audio/x-aac") || type.startsWith("audio/x-mpeg")

        /** Vrai si cette adresse peut passer par le tampon (pas une liste HLS). */
        fun canBuffer(url: String): Boolean {
            val path = Uri.parse(url).path?.lowercase().orEmpty()
            return (url.startsWith("http://") || url.startsWith("https://")) && !path.endsWith(".m3u8")
        }

        /** Adresse interne : lecture du tampon de [source] à partir de l'octet [start] (−1 : le direct). */
        fun uri(source: String, minutes: Int, start: Long = -1): String =
            Uri.Builder().scheme(SCHEME).authority("live")
                .appendQueryParameter("src", source)
                .appendQueryParameter("min", minutes.toString())
                .appendQueryParameter("start", start.toString())
                .build().toString()

        fun sourceOf(uri: Uri?): String? = uri?.takeIf { it.scheme == SCHEME }?.getQueryParameter("src")

        fun get(source: String): LiveBuffer? = synchronized(buffers) { buffers[source] }

        private fun obtain(source: String, minutes: Int): LiveBuffer = synchronized(buffers) {
            buffers[source] ?: run {
                val dir = cacheDir ?: throw IOException("tampon non initialisé")
                LiveBuffer(source, File(dir, "${source.hashCode().toUInt()}.buf"), minutes)
                    .also { buffers[source] = it; it.thread.start() }
            }
        }

        /** Arrête les enregistrements qui ne servent plus. */
        fun releaseAllExcept(keep: String?) = synchronized(buffers) {
            buffers.keys.filter { it != keep }.forEach { buffers.remove(it)?.release() }
        }
    }

    /** Source de données : sert le tampon pour les adresses « rcbuf », délègue le reste. */
    @OptIn(UnstableApi::class)
    class Source(private val upstream: DataSource) : DataSource {
        private var buffer: LiveBuffer? = null
        private var position = 0L
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) =
            upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            val src = sourceOf(dataSpec.uri) ?: return upstream.open(dataSpec).also { buffer = null }
            val minutes = dataSpec.uri.getQueryParameter("min")?.toIntOrNull() ?: 15
            val b = obtain(src, minutes)
            val requested = dataSpec.uri.getQueryParameter("start")?.toLongOrNull() ?: -1
            val start = if (requested < 0) b.written else requested.coerceIn(b.oldest, b.written)
            if (dataSpec.position == 0L) b.playStart = start
            position = start + dataSpec.position
            buffer = b
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            val b = buffer ?: return upstream.read(target, offset, length)
            if (length == 0) return 0
            val (at, n) = b.read(position, target, offset, length)
            position = at + n
            return n
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (buffer == null) upstream.responseHeaders else emptyMap()

        override fun close() {
            if (buffer == null) upstream.close()
            buffer = null
        }

        class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
            override fun createDataSource(): DataSource = Source(upstream.createDataSource())
        }
    }
}

/** État du tampon du direct en cours, pour le mini-lecteur (même processus que le service). */
object LiveBufferStatus {
    data class State(val windowMs: Long, val positionMs: Long) {
        val behindMs: Long get() = (windowMs - positionMs).coerceAtLeast(0)
    }

    val current = MutableStateFlow<State?>(null)
}

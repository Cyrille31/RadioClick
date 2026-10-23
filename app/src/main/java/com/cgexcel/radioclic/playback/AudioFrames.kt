/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import java.io.ByteArrayOutputStream

/**
 * Trames audio d'un flux de radio ordinaire (MP3 ou AAC « ADTS »).
 *
 * Chaque trame annonce sa longueur et sa durée : on peut donc découper le flux
 * en morceaux de durée connue, aux limites de trames, pour en faire une liste
 * de lecture HLS locale (voir [LiveBuffer]).
 */
object AudioFrames {

    /** Une trame : longueur en octets, durée, et format (« mp3 » ou « aac »). */
    data class Frame(val size: Int, val durationSec: Double, val format: String)

    private val mp3Bitrates = arrayOf(
        // MPEG-1 couches I, II, III
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
        // MPEG-2 / 2.5 couche I, puis couches II et III
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    )
    private val mp3SampleRates = intArrayOf(44100, 48000, 32000)
    private val aacSampleRates = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    /** Trame commençant à [pos], ou null si ce n'est pas un en-tête valide (ou s'il manque des octets). */
    fun frameAt(buf: ByteArray, pos: Int, end: Int): Frame? {
        if (end - pos < 7) return null
        val b0 = buf[pos].toInt() and 0xFF
        val b1 = buf[pos + 1].toInt() and 0xFF
        val b2 = buf[pos + 2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        // AAC ADTS : synchro sur 12 bits et couche « 00 ».
        if ((b1 and 0xF6) == 0xF0) {
            val rateIndex = (b2 shr 2) and 0x0F
            if (rateIndex >= aacSampleRates.size) return null
            val b3 = buf[pos + 3].toInt() and 0xFF
            val b4 = buf[pos + 4].toInt() and 0xFF
            val b5 = buf[pos + 5].toInt() and 0xFF
            val b6 = buf[pos + 6].toInt() and 0xFF
            val size = ((b3 and 0x03) shl 11) or (b4 shl 3) or (b5 shr 5)
            if (size < 7) return null
            val blocks = (b6 and 0x03) + 1
            return Frame(size, 1024.0 * blocks / aacSampleRates[rateIndex], "aac")
        }
        // MPEG audio (MP3 et couches I / II).
        val version = (b1 shr 3) and 0x03 // 0 : 2.5, 2 : 2, 3 : 1
        val layer = (b1 shr 1) and 0x03 // 1 : III, 2 : II, 3 : I
        if (version == 1 || layer == 0) return null
        val bitrateIndex = (b2 shr 4) and 0x0F
        val rateIndex = (b2 shr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val padding = (b2 shr 1) and 0x01
        val mpeg1 = version == 3
        val table = when {
            mpeg1 -> 3 - layer // I → 0, II → 1, III → 2
            layer == 3 -> 3
            else -> 4
        }
        val bitrate = mp3Bitrates[table][bitrateIndex] * 1000
        val sampleRate = mp3SampleRates[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val samples = when {
            layer == 3 -> 384
            layer == 2 || mpeg1 -> 1152
            else -> 576
        }
        val size = when {
            layer == 3 -> (12 * bitrate / sampleRate + padding) * 4
            layer == 1 && !mpeg1 -> 72 * bitrate / sampleRate + padding
            else -> 144 * bitrate / sampleRate + padding
        }
        if (size < 4) return null
        return Frame(size, samples.toDouble() / sampleRate, "mp3")
    }

    /** Format du flux d'après ses premiers octets (deux trames consécutives valides), ou null. */
    fun detect(buf: ByteArray, end: Int): String? {
        for (pos in 0 until end) {
            val frame = frameAt(buf, pos, end) ?: continue
            val next = frameAt(buf, pos + frame.size, end) ?: continue
            if (next.format == frame.format) return frame.format
        }
        return null
    }

    /**
     * Balise ID3 « horodatage » attendue en tête des segments audio HLS
     * (com.apple.streaming.transportStreamTimestamp, horloge à 90 kHz).
     */
    fun id3Timestamp(startSec: Double): ByteArray {
        val owner = "com.apple.streaming.transportStreamTimestamp".toByteArray(Charsets.ISO_8859_1)
        val pts = (startSec * 90_000).toLong() and 0x1FFFFFFFFL
        val data = owner + byteArrayOf(0) + ByteArray(8) { i -> (pts shr (56 - 8 * i)).toByte() }
        val frame = "PRIV".toByteArray(Charsets.ISO_8859_1) + syncSafe(data.size) + byteArrayOf(0, 0) + data
        return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(4, 0, 0) + syncSafe(frame.size) + frame
    }

    private fun syncSafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(),
        ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(),
        (n and 0x7F).toByte(),
    )

    /**
     * Découpe un flux en segments d'au moins [targetSec] secondes, aux limites de trames.
     * Les octets qui ne sont pas des trames (métadonnées, début tronqué) sont ignorés.
     */
    class Splitter(
        private val targetSec: Double,
        private val onSegment: (bytes: ByteArray, durationSec: Double, format: String) -> Unit,
    ) {
        private var buf = ByteArray(64 * 1024)
        private var len = 0
        private val segment = ByteArrayOutputStream()
        private var segmentSec = 0.0
        private var format: String? = null
        /** Vrai quand la trame précédente se terminait exactement ici. */
        private var inSync = false

        fun feed(data: ByteArray, count: Int) {
            if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
            System.arraycopy(data, 0, buf, len, count)
            len += count
            var pos = 0
            while (len - pos >= 7) {
                val frame = frameAt(buf, pos, len)?.takeIf { format == null || it.format == format }
                if (frame == null) {
                    inSync = false
                    pos++
                    continue
                }
                if (pos + frame.size > len) break
                if (!inSync) {
                    // Resynchronisation : la trame suivante doit aussi être valide (sinon fausse synchro).
                    if (len - (pos + frame.size) < 7) break
                    val next = frameAt(buf, pos + frame.size, len)
                    if (next == null || next.format != frame.format) {
                        pos++
                        continue
                    }
                }
                format = frame.format
                inSync = true
                segment.write(buf, pos, frame.size)
                segmentSec += frame.durationSec
                pos += frame.size
                if (segmentSec >= targetSec) flush()
            }
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }

        private fun flush() {
            val f = format ?: return
            onSegment(segment.toByteArray(), segmentSec, f)
            segment.reset()
            segmentSec = 0.0
        }
    }
}

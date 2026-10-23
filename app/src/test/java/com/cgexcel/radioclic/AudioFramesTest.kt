/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import com.cgexcel.radioclic.playback.AudioFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class AudioFramesTest {

    /** Trame MP3 (MPEG-1 couche III, 128 kbit/s, 44,1 kHz) : 417 octets, 1152 échantillons. */
    private fun mp3Frame(): ByteArray = ByteArray(417).also {
        it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0x00
    }

    /** Trame AAC ADTS (LC, 44,1 kHz, stéréo) de [size] octets, 1024 échantillons. */
    private fun aacFrame(size: Int): ByteArray = ByteArray(size).also {
        it[0] = 0xFF.toByte(); it[1] = 0xF1.toByte(); it[2] = 0x50
        it[3] = (0x80 or ((size shr 11) and 0x03)).toByte()
        it[4] = ((size shr 3) and 0xFF).toByte()
        it[5] = (((size and 0x07) shl 5) or 0x1F).toByte()
        it[6] = 0xFC.toByte()
    }

    @Test
    fun readsMp3AndAacHeaders() {
        val mp3 = AudioFrames.frameAt(mp3Frame(), 0, 417)!!
        assertEquals(417, mp3.size)
        assertEquals("mp3", mp3.format)
        assertEquals(1152.0 / 44100, mp3.durationSec, 1e-9)
        val aac = AudioFrames.frameAt(aacFrame(300), 0, 300)!!
        assertEquals(300, aac.size)
        assertEquals("aac", aac.format)
        assertEquals(1024.0 / 44100, aac.durationSec, 1e-9)
        assertNull(AudioFrames.frameAt(ByteArray(10), 0, 10))
    }

    @Test
    fun detectsTheStreamFormat() {
        val junk = ByteArray(25) { 0x41 }
        val mp3 = junk + mp3Frame() + mp3Frame()
        assertEquals("mp3", AudioFrames.detect(mp3, mp3.size))
        val aac = junk + aacFrame(300) + aacFrame(280)
        assertEquals("aac", AudioFrames.detect(aac, aac.size))
        assertNull(AudioFrames.detect(junk, junk.size))
    }

    @Test
    fun splitsIntoTwoSecondSegmentsAtFrameBoundaries() {
        val stream = ByteArrayOutputStream()
        stream.write(ByteArray(10) { 0x55 }) // début tronqué, ignoré
        repeat(200) { stream.write(mp3Frame()) }
        val bytes = stream.toByteArray()
        val segments = mutableListOf<Pair<Int, Double>>()
        val splitter = AudioFrames.Splitter(2.0) { data, sec, format ->
            assertEquals("mp3", format)
            segments += data.size to sec
        }
        // Arrivée par morceaux de taille quelconque, comme sur le réseau.
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(1000, bytes.size - pos)
            splitter.feed(bytes.copyOfRange(pos, pos + n), n)
            pos += n
        }
        // 2 s = 77 trames de 26,1 ms ; 200 trames donnent 2 segments complets.
        assertEquals(2, segments.size)
        segments.forEach { (size, sec) ->
            assertEquals(77 * 417, size)
            assertEquals(77 * 1152.0 / 44100, sec, 1e-9)
        }
    }

    @Test
    fun id3TimestampTag() {
        val tag = AudioFrames.id3Timestamp(10.0)
        assertEquals("ID3", String(tag, 0, 3, Charsets.ISO_8859_1))
        val owner = "com.apple.streaming.transportStreamTimestamp"
        assertEquals(10 + 10 + owner.length + 1 + 8, tag.size)
        // 10 s à 90 kHz = 900 000 = 0x0DBBA0, dans les 8 derniers octets.
        val pts = tag.takeLast(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        assertEquals(900_000L, pts)
    }
}

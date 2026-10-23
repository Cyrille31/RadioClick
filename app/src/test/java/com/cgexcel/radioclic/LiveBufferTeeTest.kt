/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import com.cgexcel.radioclic.playback.LiveBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LiveBufferTeeTest {

    @Test
    fun deliversReceivedBlocksInOrderThenEnds() {
        var closed = false
        val tee = LiveBuffer.Tee { closed = true }
        tee.offer(byteArrayOf(1, 2, 3), 3)
        tee.offer(byteArrayOf(4, 5, 9, 9), 2) // seuls les 2 premiers octets comptent
        tee.finish()
        val out = ByteArray(10)
        assertEquals(2, tee.read(out, 0, 2))
        assertEquals(1, tee.read(out, 2, 5)) // fin du premier bloc
        assertEquals(2, tee.read(out, 3, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), out.copyOf(5))
        assertEquals(-1, tee.read(out, 0, 5))
        tee.close()
        assertTrue(closed)
    }

    @Test
    fun longPauseStopsInsteadOfFillingMemory() {
        val tee = LiveBuffer.Tee { }
        val block = ByteArray(1024 * 1024)
        repeat(9) { tee.offer(block, block.size) } // plus de 8 Mo en attente
        val failed = try {
            tee.read(ByteArray(16), 0, 16)
            false
        } catch (e: IOException) {
            true
        }
        assertTrue(failed)
    }
}

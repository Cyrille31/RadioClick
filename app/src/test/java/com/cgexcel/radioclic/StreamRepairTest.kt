/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import com.cgexcel.radioclic.net.StreamRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRepairTest {

    private fun mount(path: String, title: String) = """
        <div class="roundbox"><div class="mounthead"><h3 class="mount">Mount Point $path</h3>
        <div class="right"><ul class="mountlist"><li><a class="play" href="$path.m3u">M3U</a></li></ul></div></div>
        <div class="mountcont"><table class="yellowkeys"><tbody>
        <tr><td>Stream Title:</td><td class="streamstats">$title</td></tr>
        <tr><td>Stream Description:</td><td class="streamstats">La Joie se partage</td></tr>
        <tr><td>Content Type:</td><td class="streamstats">audio/mpeg</td></tr>
        </tbody></table></div></div>
    """.trimIndent()

    private val page = "<html><body>" +
        mount("/rcf42.mp3", "RCF Saint Etienne") +
        mount("/rcf69dab.mp3", "RCF Lyon") +
        mount("/rcf74.mp3", "RCF Haute-Savoie") +
        "</body></html>"

    @Test
    fun readsMountsAndStationNames() {
        val mounts = StreamRepair.parseIcecastStatus(page)
        assertEquals(3, mounts.size)
        assertEquals(StreamRepair.Mount("/rcf69dab.mp3", "RCF Lyon"), mounts[1])
    }

    @Test
    fun findsTheMovedStream() {
        val mounts = StreamRepair.parseIcecastStatus(page)
        assertEquals("/rcf69dab.mp3", StreamRepair.pickMount(mounts, "RCF Lyon", "/rcf69.mp3"))
        assertEquals("/rcf74.mp3", StreamRepair.pickMount(mounts, "rcf haute savoie", "/rcf74old.mp3"))
        assertNull(StreamRepair.pickMount(mounts, "Radio Présence", "/presence.mp3"))
        // Le flux trouvé ne peut pas être celui qui vient d'échouer.
        assertNull(StreamRepair.pickMount(mounts, "RCF Lyon", "/rcf69dab.mp3"))
    }
}

/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import com.cgexcel.radioclic.net.Ipv4First
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class Ipv4FirstTest {
    @Test
    fun ipv4AddressesComeFirstInTheirOriginalOrder() {
        fun ip(text: String) = InetAddress.getByName(text)
        val ordered = Ipv4First.order(
            listOf(ip("2620:149:a32:f000::133"), ip("17.253.1.1"), ip("2620:149:a32:f000::138"), ip("17.253.1.2")),
        )
        assertEquals(listOf("17.253.1.1", "17.253.1.2"), ordered.take(2).map { it.hostAddress })
        assertEquals(2, ordered.drop(2).count { it is java.net.Inet6Address })
    }
}

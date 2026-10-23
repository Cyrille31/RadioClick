/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Flux de radio déplacé sur le même serveur.
 *
 * Certaines radios renomment leur flux (ex. RCF Lyon : « rcf69.mp3 » devenu
 * « rcf69dab.mp3 ») sans que les annuaires suivent : l'ancienne adresse répond
 * « introuvable ». Les serveurs Icecast publient la liste de leurs flux avec le
 * nom de chaque station (page « status.xsl ») : on y cherche celui de la radio.
 */
object StreamRepair {

    data class Mount(val path: String, val title: String)

    /** Nouvelle adresse du flux de [stationName] sur le serveur de [streamUrl], ou null. */
    suspend fun findMoved(streamUrl: String, stationName: String): String? {
        val url = streamUrl.toHttpUrlOrNull() ?: return null
        val status = url.newBuilder().encodedPath("/status.xsl").query(null).fragment(null).build()
        val mounts = runCatching { parseIcecastStatus(Http.getText(status.toString())) }.getOrNull() ?: return null
        val path = pickMount(mounts, stationName, url.encodedPath) ?: return null
        return url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
    }

    /** Points de montage et noms de station d'une page « status.xsl » d'Icecast. */
    fun parseIcecastStatus(html: String): List<Mount> {
        val text = html.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace(Regex("\\s+"), " ")
        return text.split("Mount Point ").drop(1).mapNotNull { block ->
            val path = block.substringBefore(' ').takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val title = Regex("Stream Title: (.+?) (?:Stream Description|Content Type|Mount Start|Bitrate|Stream Genre):")
                .find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            Mount(path, title)
        }
    }

    /** Flux dont le nom de station est celui recherché ; à égalité, même extension que l'ancien. */
    fun pickMount(mounts: List<Mount>, stationName: String, oldPath: String): String? {
        val wanted = norm(stationName)
        if (wanted.isEmpty()) return null
        val extension = oldPath.substringAfterLast('.', "")
        return mounts.filter { norm(it.title) == wanted && it.path != oldPath }
            .sortedByDescending { it.path.substringAfterLast('.', "") == extension }
            .firstOrNull()?.path
    }

    private fun norm(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.model

/**
 * Radios en direct proposées en un appui. Les flux Radio France sont officiels ;
 * pour les autres, la station est retrouvée dans Radio Browser au moment du choix.
 */
data class PopularStation(
    val name: String,
    val color: Long,
    val streamUrl: String? = null,
    val searchTerm: String = name,
)

val PopularStations = listOf(
    PopularStation("France Inter", 0xFFC62828, "https://icecast.radiofrance.fr/franceinter-hifi.aac"),
    PopularStation("franceinfo", 0xFFF9A825, "https://icecast.radiofrance.fr/franceinfo-hifi.aac"),
    PopularStation("France Culture", 0xFF6A1B9A, "https://icecast.radiofrance.fr/franceculture-hifi.aac"),
    PopularStation("France Musique", 0xFFAD1457, "https://icecast.radiofrance.fr/francemusique-hifi.aac"),
    PopularStation("FIP", 0xFFEF6C00, "https://icecast.radiofrance.fr/fip-hifi.aac"),
    PopularStation("Mouv'", 0xFF00838F, "https://icecast.radiofrance.fr/mouv-hifi.aac"),
    PopularStation("RTL", 0xFF1565C0, searchTerm = "RTL"),
    PopularStation("Europe 1", 0xFF455A64, searchTerm = "Europe 1"),
    PopularStation("RMC", 0xFF2E7D32, searchTerm = "RMC"),
    PopularStation("RFI", 0xFF4E342E, searchTerm = "RFI Monde"),
    PopularStation("Nostalgie", 0xFF9E9D24, searchTerm = "Nostalgie"),
)

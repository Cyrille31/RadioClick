/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.cgexcel.radioclic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Adresses IPv4 d'abord. Sur un réseau dont l'IPv6 ne répond pas, chaque adresse IPv6
 * coûtait un délai de connexion complet avant de passer à l'IPv4 : les serveurs d'Apple
 * (jusqu'à 5 adresses IPv6) dépassaient alors la limite de 30 s (émissions « timeout »,
 * vignettes absentes). L'IPv6 reste essayée si l'IPv4 échoue.
 */
object Ipv4First : Dns {
    override fun lookup(hostname: String): List<InetAddress> = order(Dns.SYSTEM.lookup(hostname))

    fun order(addresses: List<InetAddress>): List<InetAddress> = addresses.sortedBy { it is Inet6Address }
}

/** Client HTTP partagé. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4First)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent") != null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
            }
        }
        .build()

    /** Téléchargements longs (mise à jour de l'application). */
    val downloadClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()
    }

    const val USER_AGENT = "RadioClic/${BuildConfig.VERSION_NAME} (Android; CGExcel)"

    /** Télécharge le contenu texte d'une URL (thread IO). */
    suspend fun getText(url: String, userAgent: String? = null): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            if (userAgent != null) header("User-Agent", userAgent)
            header("Accept-Language", "fr-FR,fr;q=0.9")
            // Toujours la dernière version : un relais pourrait servir une copie périmée du flux.
            header("Cache-Control", "no-cache")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Erreur HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Réponse vide")
        }
    }

    /** Vrai si une connexion réseau utilisable est disponible. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Message d'erreur réseau lisible en français. */
fun Throwable.toUserMessage(): String = when (this) {
    is java.net.UnknownHostException -> "serveur introuvable (pas de réseau ?)"
    is java.net.SocketTimeoutException -> "délai dépassé"
    is java.net.ConnectException -> "connexion impossible"
    is IllegalArgumentException -> message ?: "adresse invalide"
    is kotlinx.serialization.SerializationException -> "réponse illisible"
    else -> message ?: "erreur inconnue"
}

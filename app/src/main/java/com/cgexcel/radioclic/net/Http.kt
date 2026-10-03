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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Client HTTP partagé. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build(),
            )
        }
        .build()

    const val USER_AGENT = "RadioClic/${BuildConfig.VERSION_NAME} (Android; CGExcel)"

    /** Télécharge le contenu texte d'une URL (thread IO). */
    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
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

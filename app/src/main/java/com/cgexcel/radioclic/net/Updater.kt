/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.net

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cgexcel.radioclic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Mises à jour : chaque compilation de la branche principale est publiée par GitHub
 * Actions comme « release » (étiquette v1.0.N, APK jointe). On compare N au numéro
 * de version installé, et l'APK est installée après confirmation de l'utilisateur.
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/Cyrille31/RadioClick/releases/latest"

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    /** Dernière version publiée si elle est plus récente que celle installée, sinon null. */
    suspend fun newerRelease(): Release? {
        val release = parse(Http.getText(LATEST)) ?: return null
        return release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    fun parse(json: String): Release? {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
        val tag = root.string("tag_name") ?: return null
        val code = versionCode(tag) ?: return null
        val apk = (root["assets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("browser_download_url") }
            ?.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            ?: return null
        return Release(code, tag.removePrefix("v"), apk, root.string("body").orEmpty().trim())
    }

    /** « v1.0.31 » → 31 (dernier nombre de l'étiquette). */
    fun versionCode(tag: String): Int? = Regex("(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Télécharge l'APK dans le cache de l'application. */
    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "RadioClic-${release.versionName}.apk")
        val request = Request.Builder().url(release.apkUrl).build()
        Http.downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Erreur HTTP ${response.code}")
            val body = response.body ?: throw IOException("Réponse vide")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        file
    }

    /** Ouvre l'installateur d'Android sur l'APK téléchargée. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

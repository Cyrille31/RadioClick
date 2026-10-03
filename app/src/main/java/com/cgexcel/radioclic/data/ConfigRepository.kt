/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.cgexcel.radioclic.model.AppConfig
import com.cgexcel.radioclic.model.Tile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Format JSON commun à l'enregistrement local et à l'export / import. */
val ConfigJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    classDiscriminator = "type"
}

/**
 * Stockage local de la configuration dans un fichier JSON (écriture atomique).
 * Les modifications sont appliquées immédiatement en mémoire puis écrites sur
 * disque en arrière-plan, dans l'ordre. Instance unique pour toute l'application.
 */
class ConfigRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "config.json"))
    private val lock = Any()
    private val _config = MutableStateFlow<AppConfig?>(null)
    private val pendingWrite = MutableStateFlow<AppConfig?>(null)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Configuration courante ; null tant qu'elle n'est pas chargée. */
    val config: StateFlow<AppConfig?> = _config.asStateFlow()

    init {
        ioScope.launch {
            pendingWrite.filterNotNull().conflate().collect { runCatching { writeFile(it) } }
        }
    }

    /** Configuration courante (lue sur disque au premier appel). */
    fun current(): AppConfig = _config.value ?: synchronized(lock) {
        _config.value ?: readFile().also { _config.value = it }
    }

    /** Charge la configuration hors du thread principal. */
    suspend fun load(): AppConfig = withContext(Dispatchers.IO) { current() }

    suspend fun findTile(id: String): Tile? = load().tiles.firstOrNull { it.id == id }

    /** Applique une modification ; l'écriture sur disque suit immédiatement. */
    fun update(transform: (AppConfig) -> AppConfig) {
        synchronized(lock) {
            val updated = transform(current())
            _config.value = updated
            pendingWrite.value = updated
        }
    }

    /** Exporte la configuration vers un document choisi par l'utilisateur. */
    suspend fun exportTo(uri: Uri) {
        val text = ConfigJson.encodeToString(AppConfig.serializer(), load())
        withContext(Dispatchers.IO) {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("impossible d'écrire le fichier")
        }
    }

    /** Lit un fichier d'export ; lève une exception si le contenu n'est pas valide. */
    suspend fun readImport(uri: Uri): AppConfig = withContext(Dispatchers.IO) {
        val text = appContext.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("impossible de lire le fichier")
        val imported = ConfigJson.decodeFromString(AppConfig.serializer(), text)
        require(imported.format == AppConfig.FORMAT) { "ce fichier n'est pas une configuration RadioClic" }
        imported.copy(columns = imported.columns.coerceIn(1, 4))
    }

    private fun readFile(): AppConfig {
        if (!file.baseFile.exists()) return AppConfig()
        return runCatching {
            val text = file.readFully().toString(Charsets.UTF_8)
            ConfigJson.decodeFromString(AppConfig.serializer(), text)
        }.getOrElse { AppConfig() }
    }

    private fun writeFile(config: AppConfig) {
        val bytes = ConfigJson.encodeToString(AppConfig.serializer(), config).toByteArray(Charsets.UTF_8)
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        @Volatile
        private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context).also { instance = it }
            }
    }
}

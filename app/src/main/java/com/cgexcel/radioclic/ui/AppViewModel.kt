/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cgexcel.radioclic.data.ConfigRepository
import com.cgexcel.radioclic.model.AppConfig
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.model.Tile
import com.cgexcel.radioclic.model.TilePalette
import com.cgexcel.radioclic.model.newId
import com.cgexcel.radioclic.net.toUserMessage
import com.cgexcel.radioclic.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Écrans de l'application (navigation simple par pile). */
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object TileEditor : Screen
    data object ItemEditor : Screen
    data object PodcastSearch : Screen
    data object RadioSearch : Screen
    data object About : Screen
    data object Add : Screen
}

/** Destination d'un résultat de recherche : nouvelle tuile, ou tuile en cours d'édition. */
enum class AddMode { NewTile, AddToDraft }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ConfigRepository.get(app)
    val config: StateFlow<AppConfig?> = repository.config
    val player = PlayerConnection(app)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // --- Navigation ---------------------------------------------------------

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // --- Lecture ------------------------------------------------------------

    /** Dernière tuile lancée depuis la grille (pour la signaler visuellement). */
    var lastTileId by mutableStateOf<String?>(null)
        private set

    fun play(tile: Tile) {
        lastTileId = tile.id
        player.playTile(tile)
    }

    // --- Disposition --------------------------------------------------------

    init {
        viewModelScope.launch { repository.load() }
    }

    fun setColumns(columns: Int) = repository.update { it.copy(columns = columns.coerceIn(1, 4)) }

    /** Enregistre un nouvel ordre des tuiles (glisser-déposer). */
    fun setTileOrder(tiles: List<Tile>) = repository.update { it.copy(tiles = tiles) }

    fun duplicateTile(tile: Tile) = repository.update { cfg ->
        val index = cfg.tiles.indexOfFirst { it.id == tile.id }
        val copy = tile.copy(
            id = newId(),
            title = "${tile.title} (copie)",
            items = tile.items.map { item ->
                when (item) {
                    is PlayItem.Podcast -> item.copy(id = newId())
                    is PlayItem.Live -> item.copy(id = newId())
                }
            },
        )
        cfg.copy(tiles = cfg.tiles.toMutableList().apply { add(index + 1, copy) })
    }

    fun deleteTile(tile: Tile) {
        repository.update { cfg -> cfg.copy(tiles = cfg.tiles.filterNot { it.id == tile.id }) }
        Shortcuts.disable(getApplication(), listOf(tile.id))
    }

    fun deleteAll() {
        val ids = repository.current().tiles.map { it.id }
        repository.update { AppConfig(columns = it.columns) }
        Shortcuts.disable(getApplication(), ids)
        player.stop()
    }

    // --- Ajout par recherche -----------------------------------------------

    var addMode by mutableStateOf(AddMode.NewTile)
        private set

    /** Ouvre la recherche d'émissions et de radios. */
    fun openAdd(mode: AddMode) {
        addMode = mode
        navigate(Screen.Add)
    }

    /** Ajoute l'élément choisi dans la recherche : crée la tuile ou complète celle en cours d'édition. */
    fun addFromSearch(item: PlayItem) {
        when (addMode) {
            AddMode.NewTile -> {
                val tile = Tile(title = item.title, color = nextColor(), items = listOf(item))
                repository.update { it.copy(tiles = it.tiles + tile) }
                backStack.clear()
                backStack.add(Screen.Home)
                toast(
                    if (item is PlayItem.Podcast) {
                        "Tuile « ${tile.title} » créée : un appui lit toujours le dernier épisode."
                    } else {
                        "Tuile « ${tile.title} » créée : radio en direct."
                    },
                )
            }
            AddMode.AddToDraft -> {
                updateTileDraft { it.copy(items = it.items + item, title = it.title.ifBlank { item.title }) }
                back()
            }
        }
    }

    /** Depuis la recherche : tuile vide à configurer à la main. */
    fun newEmptyTileFromAdd() {
        back()
        newTile()
    }

    private fun nextColor(): Long {
        val used = repository.current().tiles.map { it.color }.toSet()
        return TilePalette.colors.firstOrNull { it !in used }
            ?: TilePalette.colors[repository.current().tiles.size % TilePalette.colors.size]
    }

    // --- Édition d'une tuile ------------------------------------------------

    var tileDraft by mutableStateOf<Tile?>(null)
    private var tileDraftOriginal: Tile? = null
    val tileDraftIsNew: Boolean get() = tileDraftOriginal == null
    val tileDraftChanged: Boolean get() = tileDraft != (tileDraftOriginal ?: newTileTemplate)

    private var newTileTemplate: Tile? = null

    fun newTile() {
        val tile = Tile(title = "", color = nextColor())
        newTileTemplate = tile
        tileDraftOriginal = null
        tileDraft = tile
        navigate(Screen.TileEditor)
    }

    fun editTile(tile: Tile) {
        tileDraftOriginal = tile
        newTileTemplate = null
        tileDraft = tile
        navigate(Screen.TileEditor)
    }

    fun updateTileDraft(transform: (Tile) -> Tile) {
        tileDraft = tileDraft?.let(transform)
    }

    /** Enregistre la tuile en cours d'édition ; renvoie faux si elle est incomplète. */
    fun saveTileDraft(): Boolean {
        val draft = tileDraft ?: return false
        if (draft.title.isBlank()) {
            toast("Donnez un titre à la tuile.")
            return false
        }
        val tile = draft.copy(title = draft.title.trim())
        repository.update { cfg ->
            if (cfg.tiles.any { it.id == tile.id }) {
                cfg.copy(tiles = cfg.tiles.map { if (it.id == tile.id) tile else it })
            } else {
                cfg.copy(tiles = cfg.tiles + tile)
            }
        }
        Shortcuts.refresh(getApplication(), tile)
        tileDraft = null
        tileDraftOriginal = null
        back()
        return true
    }

    /** Copie une image choisie dans la galerie vers le stockage de l'application. */
    fun importTileImage(uri: Uri) = viewModelScope.launch {
        try {
            val app = getApplication<Application>()
            val file = withContext(Dispatchers.IO) {
                val dir = File(app.filesDir, "images").apply { mkdirs() }
                val target = File(dir, "${newId()}.img")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: error("image illisible")
                target
            }
            updateTileDraft { it.copy(imageUrl = Uri.fromFile(file).toString()) }
        } catch (e: Exception) {
            toast("Image impossible à utiliser : ${e.toUserMessage()}")
        }
    }

    fun discardTileDraft() {
        tileDraft = null
        tileDraftOriginal = null
        back()
    }

    // --- Édition d'un élément -----------------------------------------------

    var itemDraft by mutableStateOf<PlayItem?>(null)
    private var itemDraftIndex = -1
    val itemDraftIsNew: Boolean get() = itemDraftIndex < 0

    fun newItem(item: PlayItem) {
        itemDraftIndex = -1
        itemDraft = item
        navigate(Screen.ItemEditor)
    }

    fun editItem(index: Int) {
        val item = tileDraft?.items?.getOrNull(index) ?: return
        itemDraftIndex = index
        itemDraft = item
        navigate(Screen.ItemEditor)
    }

    fun updateItemDraft(transform: (PlayItem) -> PlayItem) {
        itemDraft = itemDraft?.let(transform)
    }

    /** Valide l'élément en cours d'édition et l'ajoute à la tuile. */
    fun commitItemDraft() {
        val item = itemDraft ?: return
        val url = when (item) {
            is PlayItem.Podcast -> item.feedUrl
            is PlayItem.Live -> item.streamUrl
        }.trim()
        val urlOk = url.startsWith("http://") || url.startsWith("https://")
        val podcastFromApple = item is PlayItem.Podcast && item.appleId != null && url.isEmpty()
        if (!urlOk && !podcastFromApple) {
            toast(
                if (item is PlayItem.Podcast) {
                    "Choisissez l'émission avec « Rechercher », ou saisissez l'adresse de son flux."
                } else {
                    "Choisissez la radio avec « Rechercher », ou saisissez l'adresse de son flux."
                },
            )
            return
        }
        val title = item.title.trim().ifBlank { Uri.parse(url).host ?: "Sans titre" }
        val clean = when (item) {
            is PlayItem.Podcast -> item.copy(title = title, feedUrl = url)
            is PlayItem.Live -> item.copy(title = title, streamUrl = url)
        }
        updateTileDraft { tile ->
            val items = tile.items.toMutableList()
            if (itemDraftIndex in items.indices) items[itemDraftIndex] = clean else items += clean
            // Titre de tuile par défaut : celui du premier élément.
            tile.copy(items = items, title = tile.title.ifBlank { clean.title })
        }
        itemDraft = null
        back()
    }

    fun removeItem(index: Int) = updateTileDraft { tile ->
        tile.copy(items = tile.items.toMutableList().apply { removeAt(index) })
    }

    fun setItems(items: List<PlayItem>) = updateTileDraft { it.copy(items = items) }

    // --- Export / import ----------------------------------------------------

    var pendingImport by mutableStateOf<AppConfig?>(null)
        private set

    fun export(uri: Uri) = viewModelScope.launch {
        try {
            repository.exportTo(uri)
            toast("Configuration exportée.")
        } catch (e: Exception) {
            toast("Export impossible : ${e.toUserMessage()}")
        }
    }

    fun readImport(uri: Uri) = viewModelScope.launch {
        try {
            pendingImport = repository.readImport(uri)
        } catch (e: Exception) {
            toast("Import impossible : ${e.toUserMessage()}")
        }
    }

    fun confirmImport() {
        val imported = pendingImport ?: return
        pendingImport = null
        repository.update { imported }
        toast("Configuration importée : ${imported.tiles.size} tuile(s).")
    }

    fun cancelImport() {
        pendingImport = null
    }

    fun toast(text: String) {
        _messages.tryEmit(text)
    }

    override fun onCleared() {
        player.release()
    }
}

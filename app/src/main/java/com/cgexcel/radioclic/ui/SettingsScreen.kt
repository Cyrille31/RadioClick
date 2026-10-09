/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.cgexcel.radioclic.model.AppConfig
import com.cgexcel.radioclic.model.Tile
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.time.LocalDate

/** Mode configuration : disposition, ajout / duplication / suppression, export / import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, config: AppConfig, snackbar: SnackbarHostState) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var tileToDelete by remember { mutableStateOf<Tile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::export) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::readImport) }

    // Copie locale pour un glisser-déposer fluide ; resynchronisée avec la configuration.
    var tiles by remember { mutableStateOf(config.tiles) }
    LaunchedEffect(config.tiles) { tiles = config.tiles }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromIndex = tiles.indexOfFirst { it.id == from.key }
        val toIndex = tiles.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            tiles = tiles.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            vm.setTileOrder(tiles)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramétrage") },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.About) }) {
                        Icon(Icons.Filled.Info, contentDescription = "À propos")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Exporter la configuration") },
                            leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                            onClick = {
                                menuOpen = false
                                exportLauncher.launch("radioclic-${LocalDate.now()}.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Importer une configuration") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = {
                                menuOpen = false
                                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Tout supprimer") },
                            leadingIcon = { Icon(Icons.Filled.DeleteForever, null) },
                            onClick = {
                                menuOpen = false
                                confirmDeleteAll = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("À propos") },
                            leadingIcon = { Icon(Icons.Filled.Info, null) },
                            onClick = {
                                menuOpen = false
                                vm.navigate(Screen.About)
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.openAdd(AddMode.NewTile) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Ajouter une tuile") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(config.columns.coerceAtMost(2)),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Réglages en tête de la liste, qui défile entièrement.
                item(key = "settings", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Nombre de colonnes", style = MaterialTheme.typography.titleSmall)
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            (1..4).forEach { n ->
                                SegmentedButton(
                                    selected = config.columns == n,
                                    onClick = { vm.setColumns(n) },
                                    shape = SegmentedButtonDefaults.itemShape(index = n - 1, count = 4),
                                ) { Text("$n") }
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Sans publicité", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Lit les émissions sans la publicité insérée au début, quand c'est possible (Radio France).",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(checked = config.skipAds, onCheckedChange = vm::setSkipAds)
                        }
                        ChoiceSetting(
                            title = "Boutons avancer / reculer",
                            values = listOf(5, 10, 15, 30, 60),
                            selected = config.seekSeconds,
                            label = { "$it s" },
                            onSelect = vm::setSeekSeconds,
                        )
                        ChoiceSetting(
                            title = "Retour possible dans un direct Radio France",
                            values = listOf(0, 15, 30, 60),
                            selected = config.liveRewindMinutes,
                            label = { if (it == 0) "Non" else "$it min" },
                            onSelect = vm::setLiveRewindMinutes,
                        )
                        Text(
                            "Sauvegarde des tuiles",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "Enregistre toutes vos tuiles dans un fichier (ex. Téléchargements), à restaurer après une réinstallation.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { exportLauncher.launch("radioclic-${LocalDate.now()}.json") },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.FileUpload, null, Modifier.size(18.dp))
                                Text("Sauvegarder", Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                                Text("Restaurer", Modifier.padding(start = 6.dp))
                            }
                        }
                        Text(
                            if (tiles.isEmpty()) {
                                "Ajoutez une première tuile avec le bouton en bas de l'écran."
                            } else {
                                "Touchez une tuile pour la modifier. Appui long puis glisser pour la déplacer (aussi possible sur l'écran principal)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(tiles, key = { it.id }) { tile ->
                    ReorderableItem(reorderState, key = tile.id) { isDragging ->
                        TileCard(
                            tile = tile,
                            columns = config.columns.coerceAtMost(2),
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .scale(if (isDragging) 1.05f else 1f),
                            onClick = { vm.editTile(tile) },
                            footer = {
                                TileEditBar(
                                    onEdit = { vm.editTile(tile) },
                                    onDuplicate = { vm.duplicateTile(tile) },
                                    onDelete = { tileToDelete = tile },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    tileToDelete?.let { tile ->
        AlertDialog(
            onDismissRequest = { tileToDelete = null },
            title = { Text("Supprimer la tuile ?") },
            text = { Text("« ${tile.title} » sera définitivement supprimée.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTile(tile)
                    tileToDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { tileToDelete = null }) { Text("Annuler") } },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Tout supprimer ?") },
            text = { Text("Toutes les tuiles seront supprimées. Pensez à exporter la configuration avant.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    confirmDeleteAll = false
                }) { Text("Tout supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ChoiceSetting(
    title: String,
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        values.forEachIndexed { index, value ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = values.size),
            ) { Text(label(value), maxLines = 1) }
        }
    }
}

@Composable
private fun TileEditBar(onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Modifier")
        }
        IconButton(onClick = onDuplicate, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Dupliquer")
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
        }
    }
}

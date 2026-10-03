/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cgexcel.radioclic.model.AppConfig
import kotlinx.coroutines.launch
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

/** Écran principal : la grille de tuiles et le mini-lecteur. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, config: AppConfig, snackbar: SnackbarHostState) {
    val playerState by vm.player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RadioClic") },
                actions = {
                    IconButton(onClick = { vm.openAdd(AddMode.NewTile) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Ajouter une émission ou une radio")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Paramétrage")
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(playerState, vm.player, config.seekSeconds) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (config.tiles.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Aucune tuile pour l'instant.",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tapez simplement le nom d'une émission (ex. « journal France Inter 8h ») " +
                        "ou d'une radio : aucune adresse à connaître.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { vm.openAdd(AddMode.NewTile) }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ajouter une émission ou une radio")
                }
            }
            return@Scaffold
        }

        // Comme sur l'écran d'accueil d'Android : appui long puis glisser pour déplacer
        // une tuile ; appui long sans bouger pour ouvrir son menu.
        var tiles by remember { mutableStateOf(config.tiles) }
        LaunchedEffect(config.tiles) { tiles = config.tiles }
        var menuTileId by remember { mutableStateOf<String?>(null) }
        var movedDuringDrag by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        val gridState = rememberLazyGridState()
        val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
            val fromIndex = tiles.indexOfFirst { it.id == from.key }
            val toIndex = tiles.indexOfFirst { it.id == to.key }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                movedDuringDrag = true
                tiles = tiles.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(config.columns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tiles, key = { it.id }) { tile ->
                ReorderableItem(reorderState, key = tile.id) { isDragging ->
                    Box {
                        TileCard(
                            tile = tile,
                            columns = config.columns,
                            playing = playerState.hasMedia && vm.lastTileId == tile.id,
                            onClick = { vm.play(tile) },
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        movedDuringDrag = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        if (movedDuringDrag) vm.setTileOrder(tiles) else menuTileId = tile.id
                                    },
                                )
                                .scale(if (isDragging) 1.08f else 1f),
                        )
                        DropdownMenu(expanded = menuTileId == tile.id, onDismissRequest = { menuTileId = null }) {
                            if (Shortcuts.isSupported(context)) {
                                DropdownMenuItem(
                                    text = { Text("Ajouter à l'écran d'accueil") },
                                    leadingIcon = { Icon(Icons.Filled.AddToHomeScreen, null) },
                                    onClick = {
                                        menuTileId = null
                                        scope.launch {
                                            if (!Shortcuts.pin(context, tile)) {
                                                vm.toast("Le lanceur ne permet pas d'ajouter ce raccourci.")
                                            }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Modifier") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = {
                                    menuTileId = null
                                    vm.navigate(Screen.Settings)
                                    vm.editTile(tile)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cgexcel.radioclic.playback.AppMessages
import kotlinx.coroutines.flow.merge

/** Racine de l'interface : affiche l'écran du sommet de la pile. */
@Composable
fun RadioClicRoot(vm: AppViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        merge(vm.messages, AppMessages.messages).collect { text ->
            snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }

    BackHandler(enabled = vm.backStack.size > 1 && vm.screen != Screen.TileEditor) { vm.back() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val cfg = config
        if (cfg == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Surface
        }
        when (vm.screen) {
            Screen.Home -> HomeScreen(vm, cfg, snackbar)
            Screen.Settings -> SettingsScreen(vm, cfg, snackbar)
            Screen.TileEditor -> TileEditorScreen(vm, snackbar)
            Screen.ItemEditor -> ItemEditorScreen(vm, snackbar)
            Screen.PodcastSearch -> PodcastSearchScreen(vm)
            Screen.RadioSearch -> RadioSearchScreen(vm)
            Screen.About -> AboutScreen(vm)
            Screen.Add -> AddScreen(vm, snackbar)
        }
    }

    vm.pendingImport?.let { imported ->
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("Importer la configuration ?") },
            text = {
                Text(
                    "Le fichier contient ${imported.tiles.size} tuile(s). " +
                        "La configuration actuelle sera entièrement remplacée.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmImport) { Text("Remplacer") } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Annuler") } },
        )
    }
}

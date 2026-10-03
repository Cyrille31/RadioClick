/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.model.TilePalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Édition d'une tuile : titre, couleur, image et liste de lecture. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TileEditorScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val tile = vm.tileDraft
    if (tile == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }

    val tryLeave: () -> Unit = {
        if (vm.tileDraftChanged) {
            confirmDiscard = true
        } else {
            vm.discardTileDraft()
        }
    }
    BackHandler { tryLeave() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::importTileImage)
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val items = tile.items
        val fromIndex = items.indexOfFirst { it.id == from.key }
        val toIndex = items.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            vm.setItems(items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.tileDraftIsNew) "Nouvelle tuile" else "Modifier la tuile") },
                navigationIcon = {
                    IconButton(onClick = tryLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.saveTileDraft() }) {
                        Icon(Icons.Filled.Check, contentDescription = "Enregistrer")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                OutlinedTextField(
                    value = tile.title,
                    onValueChange = { text -> vm.updateTileDraft { it.copy(title = text) } },
                    label = { Text("Titre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "color") {
                Column {
                    Text("Couleur", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TilePalette.colors.forEach { color ->
                            val selected = tile.color == color
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(Color(color), CircleShape)
                                    .border(
                                        BorderStroke(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.onSurface else Color.Gray,
                                        ),
                                        CircleShape,
                                    )
                                    .clickable { vm.updateTileDraft { it.copy(color = color) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Filled.Check, "Choisie", tint = contentColorOn(Color(color)))
                            }
                        }
                    }
                }
            }
            item(key = "image") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TileCard(tile = tile.copy(title = tile.title.ifBlank { "Aperçu" }), columns = 2, modifier = Modifier.width(132.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Image", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (tile.imageUrl.isNullOrBlank()) "Par défaut : pochette du premier élément." else "Image personnalisée.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Filled.Image, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Choisir une photo")
                        }
                        TextButton(onClick = { urlDialog = true }) {
                            Icon(Icons.Filled.Link, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Adresse web")
                        }
                        if (!tile.imageUrl.isNullOrBlank()) {
                            TextButton(onClick = { vm.updateTileDraft { it.copy(imageUrl = null) } }) {
                                Text("Pochette par défaut")
                            }
                        }
                    }
                }
            }
            item(key = "playlist-header") {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Liste de lecture", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (tile.items.isEmpty()) {
                        "Ajoutez un ou plusieurs éléments : ils seront lus à la suite."
                    } else {
                        "Les éléments sont lus dans l'ordre. Faites glisser la poignée pour les réordonner."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(tile.items, key = { _, item -> item.id }) { index, item ->
                ReorderableItem(reorderState, key = item.id) { isDragging ->
                    Card(
                        elevation = androidx.compose.material3.CardDefaults.cardElevation(
                            defaultElevation = if (isDragging) 8.dp else 1.dp,
                        ),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.editItem(index) }
                                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                Icon(Icons.Filled.DragHandle, contentDescription = "Déplacer")
                            }
                            Icon(
                                if (item is PlayItem.Podcast) Icons.Filled.Podcasts else Icons.Filled.Radio,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${index + 1}. ${item.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(item.describe(), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.editItem(index) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Modifier")
                            }
                            IconButton(onClick = { vm.removeItem(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Retirer")
                            }
                        }
                    }
                }
            }
            item(key = "warning") {
                val endlessIndex = tile.items.indexOfFirst { it is PlayItem.Live && (it.maxMinutes ?: 0) <= 0 }
                if (endlessIndex >= 0 && endlessIndex < tile.items.lastIndex) {
                    Text(
                        "Attention : le direct n° ${endlessIndex + 1} n'a pas de durée maximale ; " +
                            "les éléments suivants ne seront jamais atteints automatiquement.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item(key = "add") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.newItem(PlayItem.Podcast()) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Podcasts, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Podcast")
                    }
                    OutlinedButton(onClick = { vm.newItem(PlayItem.Live()) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Radio, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Radio en direct")
                    }
                }
            }
            item(key = "save") {
                Button(onClick = { vm.saveTileDraft() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Enregistrer la tuile")
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Abandonner les modifications ?") },
            text = { Text("Les changements apportés à cette tuile ne seront pas enregistrés.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.discardTileDraft()
                }) { Text("Abandonner") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.saveTileDraft()
                }) { Text("Enregistrer") }
            },
        )
    }

    if (urlDialog) {
        var url by remember { mutableStateOf(tile.imageUrl?.takeIf { it.startsWith("http") }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("Image depuis le web") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Adresse de l'image (https://…)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateTileDraft { it.copy(imageUrl = url.trim().ifBlank { null }) }
                    urlDialog = false
                }) { Text("Valider") }
            },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("Annuler") } },
        )
    }
}

/** Résumé d'un élément affiché dans la liste de lecture. */
fun PlayItem.describe(): String = when (this) {
    is PlayItem.Podcast -> if (onlyToday) "Podcast · uniquement s'il est du jour" else "Podcast · dernier épisode"
    is PlayItem.Live -> maxMinutes?.takeIf { it > 0 }?.let { "Direct · $it min maximum" } ?: "Direct · sans limite"
}

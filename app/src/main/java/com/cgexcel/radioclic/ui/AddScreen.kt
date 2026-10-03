/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cgexcel.radioclic.model.Feed
import com.cgexcel.radioclic.model.ITunesPodcast
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.model.PopularStation
import com.cgexcel.radioclic.model.PopularStations
import com.cgexcel.radioclic.model.RadioStation
import com.cgexcel.radioclic.net.FeedResolver
import com.cgexcel.radioclic.net.PodcastSearch
import com.cgexcel.radioclic.net.RadioSearch
import com.cgexcel.radioclic.net.toUserMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ajout sans aucune adresse : l'utilisateur tape ce qu'il veut écouter
 * (« journal France Inter 8h »), choisit une émission ou une radio, c'est tout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var podcasts by remember { mutableStateOf<List<ITunesPodcast>>(emptyList()) }
    var radios by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var podcastError by remember { mutableStateOf<String?>(null) }
    var radioError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var allRadios by remember { mutableStateOf(false) }
    var chosenPodcast by remember { mutableStateOf<ITunesPodcast?>(null) }
    var chosenRadio by remember { mutableStateOf<RadioStation?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var busyStation by remember { mutableStateOf<String?>(null) }

    /** Radio populaire : flux officiel direct, ou recherche de la station dans Radio Browser. */
    fun pickStation(station: PopularStation) {
        val url = station.streamUrl
        if (url != null) {
            // Le logo est ajouté juste après la création de la tuile (voir fillMissingLogos).
            vm.addFromSearch(PlayItem.Live(title = station.name, streamUrl = url), station.color)
            return
        }
        if (busyStation != null) return
        busyStation = station.name
        scope.launch {
            try {
                val found = RadioSearch.search(station.searchTerm).firstOrNull()
                if (found == null) {
                    vm.toast("« ${station.name} » introuvable pour le moment.")
                } else {
                    vm.addFromSearch(
                        PlayItem.Live(
                            title = station.name,
                            streamUrl = found.streamUrl,
                            imageUrl = found.favicon.takeIf { it.isNotBlank() },
                        ),
                        station.color,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.toast("Recherche de « ${station.name} » impossible : ${e.toUserMessage()}")
            } finally {
                busyStation = null
            }
        }
    }

    // Recherche automatique pendant la saisie (après une courte pause) ou à la validation.
    LaunchedEffect(query) {
        if (query.trim().length < 3) return@LaunchedEffect
        delay(700)
        submitted = query.trim()
    }
    LaunchedEffect(submitted) {
        val term = submitted
        if (term.isEmpty()) return@LaunchedEffect
        loading = true
        podcastError = null
        radioError = null
        coroutineScope {
            val p = async { runCatching { PodcastSearch.search(term) } }
            val r = async { runCatching { RadioSearch.search(term) } }
            p.await().onSuccess { podcasts = it }.onFailure {
                podcasts = emptyList()
                podcastError = it.toUserMessage()
            }
            r.await().onSuccess { found -> radios = found.distinctBy { it.name.trim().lowercase() }.take(20); allRadios = false }.onFailure {
                radios = emptyList()
                radioError = it.toUserMessage()
            }
        }
        loading = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (vm.addMode == AddMode.NewTile) "Nouvelle tuile" else "Ajouter à la tuile")
                },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Que voulez-vous écouter ?") },
                placeholder = { Text("Ex. : journal France Inter 8h") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Effacer") }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    submitted = query.trim()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focus),
            )

            Box(Modifier.fillMaxSize()) {
                when {
                    loading && podcasts.isEmpty() && radios.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    submitted.isEmpty() -> Intro(
                        onEmptyTile = vm::newEmptyTileFromAdd,
                        showEmptyTile = vm.addMode == AddMode.NewTile,
                        onStation = { station -> pickStation(station) },
                        busyStation = busyStation,
                    )
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        // Radios en direct d'abord : peu nombreuses, sinon noyées sous les émissions.
                        item { Section("Radios en direct", Icons.Filled.Radio) }
                        when {
                            radioError != null -> item { Hint("Recherche de radios impossible : $radioError") }
                            radios.isEmpty() && !loading -> item { Hint("Aucune radio en direct trouvée.") }
                        }
                        val shownRadios = if (allRadios) radios else radios.take(4)
                        items(shownRadios, key = { "r" + it.uuid + it.streamUrl }) { station ->
                            ResultLine(
                                image = station.favicon.takeIf { it.isNotBlank() },
                                icon = Icons.Filled.Radio,
                                title = station.name.trim(),
                                subtitle = listOf("En direct", station.country, station.codec)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                onClick = { keyboard?.hide(); chosenRadio = station },
                            )
                        }
                        if (!allRadios && radios.size > 4) {
                            item {
                                TextButton(onClick = { allRadios = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text("Voir les ${radios.size} radios")
                                }
                            }
                        }
                        item {
                            Section("Émissions enregistrées — le dernier épisode est lu automatiquement", Icons.Filled.Podcasts)
                        }
                        when {
                            podcastError != null -> item { Hint("Recherche d'émissions impossible : $podcastError") }
                            podcasts.isEmpty() && !loading -> item { Hint("Aucune émission trouvée. Essayez d'autres mots.") }
                        }
                        items(podcasts, key = { "p" + it.collectionId + it.feedUrl }) { podcast ->
                            ResultLine(
                                image = podcast.artwork,
                                icon = Icons.Filled.Podcasts,
                                title = podcast.name,
                                subtitle = podcast.artistName.orEmpty(),
                                onClick = { keyboard?.hide(); chosenPodcast = podcast },
                            )
                        }
                        if (loading) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    chosenPodcast?.let { podcast ->
        PodcastConfirmDialog(
            podcast = podcast,
            confirmLabel = if (vm.addMode == AddMode.NewTile) "Créer la tuile" else "Ajouter",
            onDismiss = { chosenPodcast = null },
            onConfirm = { onlyToday ->
                chosenPodcast = null
                vm.addFromSearch(
                    PlayItem.Podcast(
                        title = podcast.name,
                        feedUrl = podcast.feedUrl.orEmpty(),
                        appleId = podcast.collectionId.takeIf { it > 0 },
                        imageUrl = podcast.artwork,
                        onlyToday = onlyToday,
                    ),
                )
            },
        )
    }

    chosenRadio?.let { station ->
        AlertDialog(
            onDismissRequest = { chosenRadio = null },
            icon = { Icon(Icons.Filled.Radio, null) },
            title = { Text(station.name.trim()) },
            text = { Text("Radio en direct. Un appui sur la tuile lance le direct.") },
            confirmButton = {
                TextButton(onClick = {
                    chosenRadio = null
                    vm.addFromSearch(
                        PlayItem.Live(
                            title = station.name.trim(),
                            streamUrl = station.streamUrl,
                            imageUrl = station.favicon.takeIf { it.isNotBlank() },
                        ),
                    )
                }) { Text(if (vm.addMode == AddMode.NewTile) "Créer la tuile" else "Ajouter") }
            },
            dismissButton = { TextButton(onClick = { chosenRadio = null }) { Text("Annuler") } },
        )
    }
}

/** Confirmation d'une émission : derniers épisodes (pour vérifier) et option « du jour ». */
@Composable
private fun PodcastConfirmDialog(
    podcast: ITunesPodcast,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (onlyToday: Boolean) -> Unit,
) {
    var feed by remember { mutableStateOf<Feed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var onlyToday by remember { mutableStateOf(false) }

    LaunchedEffect(podcast.collectionId, podcast.feedUrl) {
        try {
            feed = FeedResolver.fetch(podcast.feedUrl.orEmpty(), podcast.collectionId.takeIf { it > 0 })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.toUserMessage()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(podcast.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (podcast.artwork != null) {
                        AsyncImage(
                            model = podcast.artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(podcast.artistName.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
                Text("Derniers épisodes", style = MaterialTheme.typography.titleSmall)
                val f = feed
                when {
                    error != null -> Text(
                        "Impossible d'afficher les épisodes ($error). Vous pouvez quand même l'ajouter.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    f == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    f.episodes.isEmpty() -> Text("Aucun épisode pour l'instant.")
                    else -> f.episodes.take(3).forEachIndexed { i, ep ->
                        Column {
                            Text(
                                ep.title,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(formatDate(ep.pubDate), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "À chaque appui, l'application va chercher le dernier épisode publié : rien à mettre à jour.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                ChoiceLine(!onlyToday, "Toujours le dernier épisode") { onlyToday = false }
                ChoiceLine(onlyToday, "Seulement s'il est du jour (sinon il est sauté)") { onlyToday = true }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(onlyToday) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun ChoiceLine(selected: Boolean, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intro(
    onEmptyTile: () -> Unit,
    showEmptyTile: Boolean,
    onStation: (PopularStation) -> Unit,
    busyStation: String?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Tapez le nom d'une émission ou d'une radio.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Exemples : « journal France Inter 8h », « revue de presse », « France Culture ».\n" +
                "Aucune adresse à connaître : le dernier épisode est retrouvé automatiquement à chaque écoute.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Radios en direct, en un appui",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PopularStations.forEach { station ->
                val bg = Color(station.color)
                AssistChip(
                    onClick = { onStation(station) },
                    label = { Text(if (busyStation == station.name) "${station.name}…" else station.name) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = bg, labelColor = contentColorOn(bg)),
                    border = null,
                )
            }
        }
        Text(
            "Toute autre radio : tapez son nom dans la recherche.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        if (showEmptyTile) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onEmptyTile) { Text("Créer une tuile vide (configuration manuelle)") }
        }
    }
}

@Composable
private fun Section(text: String, icon: ImageVector) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ResultLine(image: String?, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null)
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

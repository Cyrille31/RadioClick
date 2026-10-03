/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cgexcel.radioclic.model.Feed
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.net.FeedResolver
import com.cgexcel.radioclic.net.toUserMessage
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Édition d'un élément de liste de lecture (podcast ou radio en direct). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val item = vm.itemDraft
    if (item == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val isPodcast = item is PlayItem.Podcast
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isPodcast && vm.itemDraftIsNew -> "Ajouter un podcast"
                            isPodcast -> "Modifier le podcast"
                            vm.itemDraftIsNew -> "Ajouter une radio"
                            else -> "Modifier la radio"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Annuler")
                    }
                },
                actions = {
                    IconButton(onClick = vm::commitItemDraft) {
                        Icon(Icons.Filled.Check, contentDescription = "Valider")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = item.title,
                onValueChange = { text ->
                    vm.updateItemDraft {
                        when (it) {
                            is PlayItem.Podcast -> it.copy(title = text)
                            is PlayItem.Live -> it.copy(title = text)
                        }
                    }
                },
                label = { Text("Titre affiché") },
                placeholder = { Text(if (isPodcast) "Ex. : Journal de 8h" else "Ex. : France Inter en direct") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            when (item) {
                is PlayItem.Podcast -> PodcastFields(vm, item)
                is PlayItem.Live -> LiveFields(vm, item)
            }
            Button(onClick = vm::commitItemDraft, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.itemDraftIsNew) "Ajouter à la liste de lecture" else "Valider")
            }
        }
    }
}

@Composable
private fun PodcastFields(vm: AppViewModel, item: PlayItem.Podcast) {
    OutlinedButton(onClick = { vm.navigate(Screen.PodcastSearch) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Search, null)
        Spacer(Modifier.width(8.dp))
        Text(if (item.hasSource) "Choisir une autre émission" else "Rechercher l'émission")
    }
    Text(
        if (item.hasSource) {
            "Le dernier épisode est retrouvé automatiquement à chaque écoute."
        } else {
            "Tapez son nom (ex. « journal France Inter 8h ») : aucune adresse à connaître."
        },
        style = MaterialTheme.typography.bodySmall,
    )

    Text("Épisode à lire", style = MaterialTheme.typography.titleSmall)
    ChoiceRow(
        selected = !item.onlyToday,
        text = "Dernier épisode",
        detail = "Toujours lire l'épisode le plus récent.",
        onClick = { vm.updateItemDraft { (it as PlayItem.Podcast).copy(onlyToday = false) } },
    )
    ChoiceRow(
        selected = item.onlyToday,
        text = "Uniquement s'il est du jour",
        detail = "Si le dernier épisode date d'avant aujourd'hui, l'élément est sauté.",
        onClick = { vm.updateItemDraft { (it as PlayItem.Podcast).copy(onlyToday = true) } },
    )

    FeedPreview(vm, item)

    AdvancedSection("Adresse du flux RSS (facultatif)") {
        OutlinedTextField(
            value = item.feedUrl,
            // Une adresse saisie à la main remplace l'émission trouvée par la recherche.
            onValueChange = { url -> vm.updateItemDraft { (it as PlayItem.Podcast).copy(feedUrl = url.trim(), appleId = null) } },
            label = { Text("Adresse du flux RSS") },
            placeholder = { Text("https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Bloc repliable pour les réglages réservés aux utilisateurs avertis. */
@Composable
private fun AdvancedSection(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "▾ $title" else "▸ $title")
    }
    if (open) content()
}

/** Aperçu des derniers épisodes du flux, pour vérifier que c'est le bon. */
@Composable
private fun FeedPreview(vm: AppViewModel, item: PlayItem.Podcast) {
    var feed by remember { mutableStateOf<Feed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val url = item.feedUrl
    val appleId = item.appleId

    LaunchedEffect(url, appleId, reload) {
        feed = null
        error = null
        if (!item.hasSource) return@LaunchedEffect
        delay(500)
        loading = true
        try {
            val loaded = FeedResolver.fetch(url, appleId)
            feed = loaded
            vm.updateItemDraft {
                val p = it as PlayItem.Podcast
                p.copy(
                    title = p.title.ifBlank { loaded.title },
                    imageUrl = p.imageUrl ?: loaded.imageUrl,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Flux illisible : ${e.toUserMessage()}"
        } finally {
            loading = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Aperçu du flux", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (item.hasSource) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Recharger") }
        }
    }
    when {
        !item.hasSource -> Text(
            "Recherchez l'émission pour voir ses derniers épisodes.",
            style = MaterialTheme.typography.bodySmall,
        )
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
        feed != null -> {
            val f = feed!!
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.imageUrl != null) {
                            AsyncImage(
                                model = f.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(f.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (f.episodes.isEmpty()) Text("Aucun épisode audio dans ce flux.")
                    f.episodes.take(8).forEachIndexed { i, ep ->
                        Column {
                            Text(
                                ep.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(formatDate(ep.pubDate), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' HH'h'mm", Locale.FRENCH)

/** Date d'un épisode en clair (« Aujourd'hui · samedi 3 octobre 2026 à 08h00 »). */
fun formatDate(millis: Long?): String {
    if (millis == null) return "date inconnue"
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    val prefix = if (date.toLocalDate() == LocalDate.now(zone)) "Aujourd'hui · " else ""
    return prefix + dateFormat.format(date)
}

@Composable
private fun LiveFields(vm: AppViewModel, item: PlayItem.Live) {
    OutlinedButton(onClick = { vm.navigate(Screen.RadioSearch) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Search, null)
        Spacer(Modifier.width(8.dp))
        Text(if (item.streamUrl.isBlank()) "Rechercher la radio" else "Choisir une autre radio")
    }
    if (item.streamUrl.isBlank()) {
        Text("Tapez simplement son nom (ex. « France Inter »).", style = MaterialTheme.typography.bodySmall)
    }
    AdvancedSection("Adresse du flux audio (facultatif)") {
        OutlinedTextField(
            value = item.streamUrl,
            onValueChange = { url -> vm.updateItemDraft { (it as PlayItem.Live).copy(streamUrl = url.trim()) } },
            label = { Text("Adresse du flux audio") },
            placeholder = { Text("https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val limited = (item.maxMinutes ?: 0) > 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Durée maximale", style = MaterialTheme.typography.titleSmall)
            Text(
                if (limited) {
                    "Après cette durée, l'élément suivant est lancé."
                } else {
                    "Sans durée, le direct est le dernier élément de l'enchaînement."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = limited,
            onCheckedChange = { on ->
                vm.updateItemDraft { (it as PlayItem.Live).copy(maxMinutes = if (on) 10 else null) }
            },
        )
    }
    if (limited) {
        var text by remember { mutableStateOf(item.maxMinutes.toString()) }
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value.filter(Char::isDigit).take(3)
                val minutes = text.toIntOrNull()
                if (minutes != null && minutes > 0) {
                    vm.updateItemDraft { (it as PlayItem.Live).copy(maxMinutes = minutes) }
                }
            },
            label = { Text("Minutes") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, text: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(0.dp))
}

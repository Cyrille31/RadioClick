/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cgexcel.radioclic.model.ITunesPodcast
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.model.RadioStation
import com.cgexcel.radioclic.net.PodcastSearch
import com.cgexcel.radioclic.net.RadioSearch
import com.cgexcel.radioclic.net.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Recherche de podcasts (API iTunes Search). */
@Composable
fun PodcastSearchScreen(vm: AppViewModel) {
    SearchScreen(
        title = "Rechercher un podcast",
        hint = "Nom de l'émission, de la radio…",
        icon = Icons.Filled.Podcasts,
        search = PodcastSearch::search,
        key = { it.collectionId.toString() + it.feedUrl },
        row = { podcast: ITunesPodcast ->
            ResultRow(
                image = podcast.artwork,
                icon = Icons.Filled.Podcasts,
                title = podcast.name,
                subtitle = podcast.artistName.orEmpty(),
            )
        },
        onPick = { podcast ->
            vm.updateItemDraft {
                val p = it as? PlayItem.Podcast ?: return@updateItemDraft it
                p.copy(
                    title = p.title.ifBlank { podcast.name },
                    feedUrl = podcast.feedUrl.orEmpty(),
                    imageUrl = podcast.artwork,
                )
            }
            vm.back()
        },
        onBack = vm::back,
    )
}

/** Recherche de radios (API Radio Browser). */
@Composable
fun RadioSearchScreen(vm: AppViewModel) {
    SearchScreen(
        title = "Rechercher une radio",
        hint = "Nom de la station…",
        icon = Icons.Filled.Radio,
        search = RadioSearch::search,
        key = { it.uuid.ifBlank { it.streamUrl } },
        row = { station: RadioStation ->
            val details = listOfNotNull(
                station.country.takeIf { it.isNotBlank() },
                station.codec.takeIf { it.isNotBlank() }?.let { codec ->
                    if (station.bitrate > 0) "$codec ${station.bitrate} kb/s" else codec
                },
                station.tags.takeIf { it.isNotBlank() }?.split(',')?.take(3)?.joinToString(", "),
            ).joinToString(" · ")
            ResultRow(
                image = station.favicon.takeIf { it.isNotBlank() },
                icon = Icons.Filled.Radio,
                title = station.name.trim(),
                subtitle = details,
            )
        },
        onPick = { station ->
            vm.updateItemDraft {
                val live = it as? PlayItem.Live ?: return@updateItemDraft it
                live.copy(
                    title = live.title.ifBlank { station.name.trim() },
                    streamUrl = station.streamUrl,
                    imageUrl = station.favicon.takeIf { url -> url.isNotBlank() },
                )
            }
            vm.back()
        },
        onBack = vm::back,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SearchScreen(
    title: String,
    hint: String,
    icon: ImageVector,
    search: suspend (String) -> List<T>,
    key: (T) -> String,
    row: @Composable (T) -> Unit,
    onPick: (T) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<T>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    fun launchSearch() {
        val term = query.trim()
        if (term.isEmpty()) return
        keyboard?.hide()
        job?.cancel()
        job = scope.launch {
            loading = true
            error = null
            try {
                results = search(term)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                results = null
                error = "Recherche impossible : ${e.toUserMessage()}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(hint) },
                singleLine = true,
                leadingIcon = { Icon(icon, null) },
                trailingIcon = {
                    IconButton(onClick = ::launchSearch) { Icon(Icons.Filled.Search, "Rechercher") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { launchSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focus),
            )
            Box(Modifier.fillMaxSize()) {
                val list = results
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> Message(error!!, Modifier.align(Alignment.Center))
                    list == null -> Message("Saisissez des mots-clés puis lancez la recherche.", Modifier.align(Alignment.Center))
                    list.isEmpty() -> Message("Aucun résultat.", Modifier.align(Alignment.Center))
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(list, key = key) { result ->
                            Box(Modifier.clickable { onPick(result) }) { row(result) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(32.dp), textAlign = TextAlign.Center)
}

@Composable
private fun ResultRow(image: String?, icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier
            .fillMaxWidth()
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
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

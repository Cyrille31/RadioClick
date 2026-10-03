/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cgexcel.radioclic.playback.PlayerConnection
import com.cgexcel.radioclic.playback.PlayerUiState

/** Mini-lecteur en bas de l'écran principal. */
@Composable
fun MiniPlayer(state: PlayerUiState, player: PlayerConnection) {
    if (!state.hasMedia && state.loadingTile == null) return
    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (state.loadingTile != null) {
                Text(
                    "Préparation de « ${state.loadingTile} »…",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
            }
            if (state.hasMedia) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.artwork != null) {
                        AsyncImage(
                            model = state.artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val position = if (state.count > 1) "${state.index + 1} / ${state.count}" else ""
                        val detail = listOf(position, state.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
                        if (detail.isNotBlank()) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = player::previous, enabled = state.hasPrevious) {
                        Icon(Icons.Filled.SkipPrevious, "Précédent", Modifier.size(32.dp))
                    }
                    FilledIconButton(onClick = player::togglePlayPause, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (state.isPlaying) "Pause" else "Lecture",
                            Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = player::next, enabled = state.hasNext) {
                        Icon(Icons.Filled.SkipNext, "Suivant", Modifier.size(32.dp))
                    }
                    IconButton(onClick = player::stop) {
                        Icon(Icons.Filled.Stop, "Arrêter", Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

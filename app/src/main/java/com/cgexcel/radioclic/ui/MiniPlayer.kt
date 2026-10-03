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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cgexcel.radioclic.playback.PlayerConnection
import com.cgexcel.radioclic.playback.PlayerUiState

/** Mini-lecteur en bas de l'écran principal : titre, chrono, barre de temps et commandes. */
@Composable
fun MiniPlayer(state: PlayerUiState, player: PlayerConnection, seekSeconds: Int) {
    if (!state.hasMedia && state.loadingTile == null) return
    val stepMs = seekSeconds * 1000L
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
            if (!state.hasMedia) return@Column

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
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val canSeek = state.isSeekable && state.durationMs > 0
            if (canSeek) TimeBar(state, player)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::previous, enabled = state.hasPrevious) {
                    Icon(Icons.Filled.SkipPrevious, "Élément précédent", Modifier.size(30.dp))
                }
                if (canSeek) {
                    SeekButton(back = true, seconds = seekSeconds) { player.seekBy(-stepMs) }
                }
                FilledIconButton(onClick = player::togglePlayPause, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Lecture",
                        Modifier.size(32.dp),
                    )
                }
                if (canSeek) {
                    SeekButton(back = false, seconds = seekSeconds) { player.seekBy(stepMs) }
                }
                IconButton(onClick = player::next, enabled = state.hasNext) {
                    Icon(Icons.Filled.SkipNext, "Élément suivant", Modifier.size(30.dp))
                }
                IconButton(onClick = player::stop) {
                    Icon(Icons.Filled.Stop, "Arrêter", Modifier.size(30.dp))
                }
            }
        }
    }
}

/** Chrono et barre de temps ; pour un direct, l'avance prise sur le direct et un retour au direct. */
@Composable
private fun TimeBar(state: PlayerUiState, player: PlayerConnection) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.toFloat()
    val shown = dragging ?: state.positionMs.toFloat().coerceIn(0f, duration)
    val tabular = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")

    Slider(
        value = shown,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { player.seekTo(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..duration,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isLive) {
            val behind = (duration - shown).toLong()
            Text(
                if (behind < 10_000) "En direct" else "Direct −${formatTime(behind)}",
                style = tabular,
                modifier = Modifier.weight(1f),
            )
            if (behind >= 10_000) {
                AssistChip(onClick = player::goLive, label = { Text("Revenir au direct") })
            }
        } else {
            Text(formatTime(shown.toLong()), style = tabular, modifier = Modifier.weight(1f))
            Text("−" + formatTime((duration - shown).toLong()), style = tabular)
            Spacer(Modifier.width(12.dp))
            Text(formatTime(duration.toLong()), style = tabular)
        }
    }
}

@Composable
private fun SeekButton(back: Boolean, seconds: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (back) Icons.Filled.Replay10 else Icons.Filled.Forward10,
                if (back) "Reculer de $seconds secondes" else "Avancer de $seconds secondes",
                Modifier.size(22.dp),
            )
            Text(if (back) "−$seconds" else "+$seconds", fontSize = 10.sp, lineHeight = 10.sp)
        }
    }
}

/** « 4:05 » ou « 1:02:03 ». */
fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cgexcel.radioclic.model.Tile

/**
 * Tuile de la grille : couleur, image optionnelle (pochette par défaut) et titre.
 * En une colonne, la tuile est horizontale ; sinon carrée.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TileCard(
    tile: Tile,
    columns: Int,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val background = Color(tile.color)
    val content = contentColorOn(background)
    val image = tile.displayImage
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = background, contentColor = content),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.then(clickModifier)) {
            if (columns == 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (image != null) {
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(
                        tile.title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (playing) PlayingIcon()
                }
            } else {
                val titleSize = when (columns) {
                    2 -> 20.sp
                    3 -> 16.sp
                    else -> 13.sp
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(if (columns >= 4) 6.dp else 10.dp),
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (image != null) {
                            AsyncImage(
                                model = image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            tile.title,
                            fontSize = titleSize,
                            lineHeight = titleSize * 1.15f,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = if (image != null) 2 else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (playing) {
                        Box(Modifier.align(Alignment.TopEnd)) { PlayingIcon() }
                    }
                }
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun PlayingIcon() {
    Icon(
        Icons.Filled.GraphicEq,
        contentDescription = "En cours de lecture",
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.secondary,
    )
}

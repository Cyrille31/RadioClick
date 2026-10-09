/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cgexcel.radioclic.BuildConfig
import com.cgexcel.radioclic.R

const val GOOD_DEED_SENTENCE =
    "En échange, une seule chose vous est demandée, sur l'honneur : faire une bonne action chaque jour. " +
        "Aider un voisin, sourire à un inconnu, ramasser un papier… c'est vous qui voyez."

private const val WEBSITE = "https://cgexcel.wordpress.com/"

/** Écran « À propos » : nom, version, auteur, licence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(vm: AppViewModel) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("À propos") },
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A5F))) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp),
                )
            }
            Text("RadioClic", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }, enabled = !vm.updateBusy) {
                Text(if (vm.updateBusy) "Recherche en cours…" else "Rechercher une mise à jour")
            }
            HorizontalDivider()
            Text("CGExcel", style = MaterialTheme.typography.titleMedium)
            Text("Cyrille Gindre — © 2026", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { uriHandler.openUri(WEBSITE) }) { Text(WEBSITE) }
            HorizontalDivider()
            Text(
                "Licence : MIT + BAL 1.0 — Bonne Action License",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                GOOD_DEED_SENTENCE,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            )
            HorizontalDivider()
            Text(
                "Sans compte, sans publicité, sans collecte de données.\n" +
                    "Recherche de podcasts : iTunes Search API (Apple).\n" +
                    "Recherche de radios : Radio Browser (radio-browser.info).",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

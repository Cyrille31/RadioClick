/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.cgexcel.radioclic.data.ConfigJson
import com.cgexcel.radioclic.data.ConfigRepository
import com.cgexcel.radioclic.model.Tile
import com.cgexcel.radioclic.playback.PlaybackService
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Activité invisible ouverte par un raccourci épinglé : lance la tuile
 * sans afficher la grille, puis se ferme.
 */
class ShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tileId = intent?.getStringExtra(EXTRA_TILE_ID)
        if (tileId == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val tile = ConfigRepository.get(this@ShortcutActivity).findTile(tileId)
            if (tile == null) {
                Toast.makeText(this@ShortcutActivity, "Cette tuile n'existe plus.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            Toast.makeText(
                this@ShortcutActivity,
                "Lancement de « ${tile.title} »…",
                Toast.LENGTH_SHORT,
            ).show()
            val token = SessionToken(
                this@ShortcutActivity,
                ComponentName(this@ShortcutActivity, PlaybackService::class.java),
            )
            val future = MediaController.Builder(this@ShortcutActivity, token).buildAsync()
            try {
                val controller = future.await()
                val args = Bundle().apply {
                    putString(PlaybackService.KEY_TILE_JSON, ConfigJson.encodeToString(Tile.serializer(), tile))
                }
                // Attend que l'enchaînement soit prêt et lancé avant de se déconnecter.
                controller.sendCustomCommand(SessionCommand(PlaybackService.CMD_PLAY_TILE, Bundle.EMPTY), args)
                    .await()
            } catch (e: Exception) {
                Toast.makeText(this@ShortcutActivity, "Lecture impossible.", Toast.LENGTH_LONG).show()
            } finally {
                MediaController.releaseFuture(future)
                finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_TILE = "com.cgexcel.radioclic.PLAY_TILE"
        const val EXTRA_TILE_ID = "tileId"
    }
}

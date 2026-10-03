/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.cgexcel.radioclic.data.ConfigJson
import com.cgexcel.radioclic.model.Tile
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** État affiché par le mini-lecteur. */
data class PlayerUiState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val artwork: Uri? = null,
    val index: Int = 0,
    val count: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** Titre de la tuile en cours de préparation (résolution des flux), sinon null. */
    val loadingTile: String? = null,
)

/** Connexion de l'interface au service de lecture (MediaController). */
@OptIn(UnstableApi::class)
class PlayerConnection(context: Context) {

    private val appContext = context.applicationContext
    private val future: ListenableFuture<MediaController> = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()

    private var controller: MediaController? = null
    private var pendingTile: Tile? = null
    private var loadingToken = 0

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            pendingTile?.let { pendingTile = null; playTile(it) }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun playTile(tile: Tile) {
        val c = controller
        if (c == null) {
            pendingTile = tile
            _state.update { it.copy(loadingTile = tile.title) }
            return
        }
        val token = ++loadingToken
        _state.update { it.copy(loadingTile = tile.title) }
        val args = Bundle().apply {
            putString(PlaybackService.KEY_TILE_JSON, ConfigJson.encodeToString(Tile.serializer(), tile))
        }
        val result = c.sendCustomCommand(SessionCommand(PlaybackService.CMD_PLAY_TILE, Bundle.EMPTY), args)
        result.addListener({
            if (token == loadingToken) _state.update { it.copy(loadingTile = null) }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun previous() {
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        controller = null
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val meta = item?.mediaMetadata
        _state.update {
            it.copy(
                hasMedia = item != null,
                isPlaying = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                title = meta?.title?.toString().orEmpty(),
                subtitle = meta?.artist?.toString().orEmpty(),
                artwork = meta?.artworkUri,
                index = c.currentMediaItemIndex,
                count = c.mediaItemCount,
                hasPrevious = c.hasPreviousMediaItem(),
                hasNext = c.hasNextMediaItem(),
            )
        }
    }
}

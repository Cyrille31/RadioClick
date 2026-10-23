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
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isLive: Boolean = false,
    val isSeekable: Boolean = false,
    /** Retard sur le direct choisi par l'utilisateur (ms) : 0, 15 s, 30 s… (plus le temps de pause). */
    val liveBehindMs: Long = 0,
    /** Direct lu au fil de l'eau, qu'on peut faire reculer en passant sur son enregistrement. */
    val canShiftLive: Boolean = false,
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
    private var pendingDay: java.time.LocalDate? = null

    // Retard sur le direct, tenu ici plutôt que déduit de la fenêtre HLS : celle-ci grandit par
    // segments de 4 s, ce qui faisait osciller l'affichage (−14 s, −17 s… au lieu de −15 s).
    private var liveMediaId: String? = null
    private var liveBehindMs = 0L
    private var pausedAtMs: Long? = null
    /** Écart naturel entre la fin de la fenêtre et la lecture « en direct » (position par défaut). */
    private var liveLagMs = 0L
    private var loadingToken = 0

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    init {
        handler.post(ticker)
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            pendingTile?.let { pendingTile = null; playTile(it, pendingDay) }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun playTile(tile: Tile, day: java.time.LocalDate? = null) {
        val c = controller
        if (c == null) {
            pendingTile = tile
            pendingDay = day
            _state.update { it.copy(loadingTile = tile.title) }
            return
        }
        val token = ++loadingToken
        _state.update { it.copy(loadingTile = tile.title) }
        val args = Bundle().apply {
            putString(PlaybackService.KEY_TILE_JSON, ConfigJson.encodeToString(Tile.serializer(), tile))
            day?.let { putLong(PlaybackService.KEY_EPOCH_DAY, it.toEpochDay()) }
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

    /** Avance (delta positif) ou recule dans l'épisode ou dans la fenêtre du direct. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        if (isTee(c)) {
            // Direct au fil de l'eau : on y est déjà pour avancer ; pour reculer, le service bascule
            // sur l'enregistrement.
            if (deltaMs >= 0) return
            liveBehindMs = currentBehind() - deltaMs
            pausedAtMs = if (c.playWhenReady) null else System.currentTimeMillis()
            val args = Bundle().apply { putLong(PlaybackService.KEY_BEHIND_MS, liveBehindMs) }
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_LIVE_SHIFT, Bundle.EMPTY), args)
            refresh()
            return
        }
        if (!c.isCurrentMediaItemSeekable) return
        if (isRealLive(c)) {
            // Direct : ni avant le début de la fenêtre, ni au-delà du direct.
            val behind = currentBehind()
            if (behind - deltaMs <= 0) return goLive()
            val target = (c.currentPosition + deltaMs).coerceAtLeast(0)
            liveBehindMs = behind - (target - c.currentPosition)
            pausedAtMs = if (c.playWhenReady) null else System.currentTimeMillis()
            c.seekTo(target)
            refresh()
            return
        }
        val duration = c.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, duration))
        refresh()
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        if (!c.isCurrentMediaItemSeekable) return
        if (isRealLive(c)) {
            val duration = c.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
            val behind = duration - positionMs - liveLagMs
            if (behind < 1_000) return goLive()
            liveBehindMs = behind
            pausedAtMs = if (c.playWhenReady) null else System.currentTimeMillis()
        }
        c.seekTo(positionMs.coerceAtLeast(0))
        refresh()
    }

    /** Revient au direct après avoir reculé. */
    fun goLive() {
        liveBehindMs = 0
        pausedAtMs = null
        controller?.seekToDefaultPosition()
        refresh()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        controller = null
    }

    /** Vrai direct (pas un créneau passé repris du direct). */
    private fun isRealLive(c: MediaController): Boolean =
        c.isCurrentMediaItemLive &&
            c.currentMediaItem?.mediaMetadata?.extras?.getBoolean(PlaylistBuilder.EXTRA_REPLAY) != true

    /** Direct ordinaire lu au fil de l'eau (pas encore basculé sur son enregistrement). */
    private fun isTee(c: MediaController): Boolean =
        c.currentMediaItem?.mediaMetadata?.extras?.getString(PlaylistBuilder.EXTRA_BUFFER_URL) != null

    private fun currentBehind(): Long =
        liveBehindMs + (pausedAtMs?.let { System.currentTimeMillis() - it } ?: 0)

    /** Suit le retard sur le direct : remis à zéro à chaque nouveau direct, augmenté pendant les pauses. */
    private fun trackLive(c: MediaController) {
        val id = c.currentMediaItem?.mediaId
        if (!isRealLive(c) && !isTee(c)) {
            liveMediaId = null
            return
        }
        if (id != liveMediaId) {
            liveMediaId = id
            liveBehindMs = 0
            pausedAtMs = null
        }
        val now = System.currentTimeMillis()
        if (!c.playWhenReady && pausedAtMs == null) pausedAtMs = now
        if (c.playWhenReady && pausedAtMs != null) {
            liveBehindMs += now - pausedAtMs!!
            pausedAtMs = null
        }
        if (isTee(c)) return
        val duration = c.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: return
        if (liveBehindMs == 0L && c.isPlaying) liveLagMs = (duration - c.currentPosition).coerceAtLeast(0)
    }

    private fun refresh() {
        val c = controller ?: return
        trackLive(c)
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
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { d -> d != androidx.media3.common.C.TIME_UNSET && d > 0 } ?: 0,
                isLive = isRealLive(c) || isTee(c),
                isSeekable = c.isCurrentMediaItemSeekable,
                liveBehindMs = if (isRealLive(c) || isTee(c)) currentBehind() else 0,
                canShiftLive = isTee(c),
            )
        }
    }
}

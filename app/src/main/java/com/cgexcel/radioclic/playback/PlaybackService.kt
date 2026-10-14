/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.cgexcel.radioclic.MainActivity
import com.cgexcel.radioclic.R
import com.cgexcel.radioclic.data.ConfigJson
import com.cgexcel.radioclic.data.ConfigRepository
import com.cgexcel.radioclic.model.Tile
import com.cgexcel.radioclic.net.Http
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Service de lecture : ExoPlayer + MediaSession (arrière-plan, notification,
 * écran de verrouillage, Bluetooth, focus audio).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private var loadJob: Job? = null

    /** Temps de lecture écoulé (ms) de l'élément en cours, pour les directs à durée limitée. */
    private var elapsedOnItemMs = 0L
    private var lastMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        LiveBuffer.init(this)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(LiveBuffer.Source.Factory(LiveTimeshift.Source.Factory(dataSourceFactory))),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        startDurationWatcher()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        LiveBuffer.releaseAllExcept(null)
        LiveBufferStatus.current.value = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Lance une tuile : remplace l'enchaînement en cours. */
    private suspend fun playTile(tile: Tile) {
        val name = tile.title.ifBlank { "la tuile" }
        if (tile.items.isEmpty()) {
            AppMessages.show(this, "« $name » ne contient aucun élément à lire.")
            return
        }
        if (!Http.isOnline(this)) {
            AppMessages.show(this, "Pas de connexion réseau : impossible de lancer « $name ».")
            return
        }
        player.pause()

        val config = runCatching { ConfigRepository.get(this).load() }.getOrNull()
        val options = PlaylistBuilder.Options(
            skipAds = config?.skipAds ?: true,
            liveRewindMinutes = config?.liveRewindMinutes ?: 15,
        )
        val result = PlaylistBuilder.build(tile, options)
        if (result.messages.isNotEmpty()) {
            AppMessages.show(this, result.messages.joinToString("\n"))
        }
        if (result.mediaItems.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            AppMessages.show(this, "Rien à lire pour « $name ».")
            return
        }
        elapsedOnItemMs = 0
        player.setMediaItems(result.mediaItems, 0, C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    private fun stopAndClear() {
        player.stop()
        player.clearMediaItems()
        LiveBuffer.releaseAllExcept(null)
        LiveBufferStatus.current.value = null
    }

    private fun MediaItem.bufferSource(): String? = LiveBuffer.sourceOf(localConfiguration?.uri)

    /**
     * Déplacement dans un direct enregistré : [deltaMs] relatif, ou retour au direct.
     * L'élément est relancé à la nouvelle position du tampon.
     */
    private fun seekInBuffer(deltaMs: Long, toLive: Boolean) {
        val item = player.currentMediaItem ?: return
        val src = item.bufferSource() ?: return
        val buffer = LiveBuffer.get(src) ?: return
        val rate = buffer.bytesPerSecond()
        val now = buffer.playbackPosition(player.currentPosition)
        val target = (if (toLive) buffer.written - rate else now + deltaMs * rate / 1000)
            .coerceIn(buffer.oldest + rate / 2, max(buffer.oldest, buffer.written - rate / 2))
        val minutes = item.localConfiguration?.uri?.getQueryParameter("min")?.toIntOrNull() ?: 15
        val moved = item.buildUpon().setUri(LiveBuffer.uri(src, minutes, target)).build()
        val wasPlaying = player.playWhenReady
        player.replaceMediaItem(player.currentMediaItemIndex, moved)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = wasPlaying
    }

    /** Publie la position dans le tampon du direct pour le mini-lecteur. */
    private fun publishBufferStatus() {
        val src = player.currentMediaItem?.bufferSource()
        val buffer = src?.let { LiveBuffer.get(it) }
        if (buffer == null || buffer.written == 0L) {
            LiveBufferStatus.current.value = null
            return
        }
        val rate = buffer.bytesPerSecond()
        val window = (buffer.written - buffer.oldest) * 1000 / rate
        val position = (buffer.playbackPosition(player.currentPosition) - buffer.oldest) * 1000 / rate
        LiveBufferStatus.current.value = LiveBufferStatus.State(window, position.coerceIn(0, window))
    }

    /** Passe à l'élément suivant quand un direct a atteint sa durée maximale. */
    private fun startDurationWatcher() {
        scope.launch {
            while (isActive) {
                delay(1_000)
                publishBufferStatus()
                val maxMs = player.currentMediaItem?.maxDurationMs() ?: continue
                if (!player.isPlaying) continue
                elapsedOnItemMs += 1_000
                if (elapsedOnItemMs >= maxMs) {
                    elapsedOnItemMs = 0
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else stopAndClear()
                }
            }
        }
    }

    private fun MediaItem.maxDurationMs(): Long? =
        mediaMetadata.extras?.getLong(PlaylistBuilder.EXTRA_MAX_DURATION_MS, 0L)?.takeIf { it > 0 }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Un déplacement dans le tampon relance le même élément : le compteur continue.
            if (mediaItem?.mediaId != lastMediaId) elapsedOnItemMs = 0
            lastMediaId = mediaItem?.mediaId
            LiveBuffer.releaseAllExcept(mediaItem?.bufferSource())
            publishBufferStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) stopAndClear()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Direct enregistré en échec (format non pris en charge…) : on le lit sans tampon.
            val current = player.currentMediaItem
            val direct = current?.mediaMetadata?.extras?.getString(PlaylistBuilder.EXTRA_DIRECT_URL)
            if (current != null && direct != null && current.bufferSource() != null) {
                LiveBuffer.releaseAllExcept(null)
                player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon().setUri(direct).build())
                player.prepare()
                player.play()
                return
            }
            val title = player.currentMediaItem?.mediaMetadata?.title ?: "élément"
            val reason = if (error.errorCode in 2000..2999) "réseau ou flux indisponible" else "format non lisible"
            if (player.hasNextMediaItem()) {
                AppMessages.show(this@PlaybackService, "« $title » sauté : $reason.")
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                AppMessages.show(this@PlaybackService, "« $title » : lecture impossible ($reason).")
                stopAndClear()
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) return super.onConnect(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PLAY_TILE, Bundle.EMPTY))
                .add(SessionCommand(CMD_LIVE_SEEK, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_LIVE_SEEK) {
                seekInBuffer(args.getLong(KEY_DELTA_MS), args.getBoolean(KEY_TO_LIVE))
                publishBufferStatus()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction != CMD_PLAY_TILE) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val tile = args.getString(KEY_TILE_JSON)?.let {
                runCatching { ConfigJson.decodeFromString(Tile.serializer(), it) }.getOrNull()
            } ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))

            val done = SettableFuture.create<SessionResult>()
            loadJob?.cancel()
            loadJob = scope.launch {
                try {
                    playTile(tile)
                } finally {
                    done.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return done
        }
    }

    companion object {
        /** Commande personnalisée : lancer une tuile (JSON dans KEY_TILE_JSON). */
        const val CMD_PLAY_TILE = "com.cgexcel.radioclic.PLAY_TILE"
        const val KEY_TILE_JSON = "tile"

        /** Déplacement dans un direct enregistré (KEY_DELTA_MS, ou KEY_TO_LIVE). */
        const val CMD_LIVE_SEEK = "com.cgexcel.radioclic.LIVE_SEEK"
        const val KEY_DELTA_MS = "deltaMs"
        const val KEY_TO_LIVE = "toLive"
    }
}

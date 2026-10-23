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
import com.cgexcel.radioclic.model.PlayItem
import com.cgexcel.radioclic.model.Tile
import com.cgexcel.radioclic.net.Http
import com.cgexcel.radioclic.net.StreamRepair
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    override fun onCreate() {
        super.onCreate()
        // Efface un éventuel enregistrement laissé par un arrêt brutal.
        LiveBuffer.init(cacheDir)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(LiveTimeshift.Source.Factory(dataSourceFactory)))
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
        LiveBuffer.stop()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Lance une tuile : remplace l'enchaînement en cours. */
    private suspend fun playTile(tile: Tile, day: java.time.LocalDate? = null) {
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
            day = day,
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
        LiveBuffer.stop()
    }

    /** Passe à l'élément suivant quand un direct a atteint sa durée maximale. */
    private fun startDurationWatcher() {
        scope.launch {
            var pausedMs = 0L
            while (isActive) {
                delay(1_000)
                // Pause prolongée : l'enregistrement d'un direct est arrêté et ses fichiers supprimés
                // (il reprend au direct à la relecture).
                pausedMs = if (player.playWhenReady || !LiveBuffer.isRecording) 0 else pausedMs + 1_000
                if (pausedMs > MAX_BUFFER_PAUSE_MS) {
                    LiveBuffer.stop()
                    pausedMs = 0
                }
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
            elapsedOnItemMs = 0
            // L'enregistrement ne sert qu'au direct en cours : libéré dès qu'on passe à autre chose.
            val uri = mediaItem?.localConfiguration?.uri
            if (uri == null || !LiveBuffer.handles(uri)) LiveBuffer.stop()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) stopAndClear()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Trop longtemps en arrière (pause plus longue que l'enregistrement) : retour au direct.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && tryRepair()) return
            skipAfterError(error)
        }
    }

    /** Directs déjà réparés (ou tentés) depuis le démarrage du service : une seule tentative chacun. */
    private val repairAttempts = mutableSetOf<String>()

    /**
     * Flux introuvable : cherche sa nouvelle adresse sur le même serveur. Si elle existe,
     * relance la lecture et met la tuile à jour. Vrai si une tentative est lancée.
     */
    private fun tryRepair(): Boolean {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return false
        val extras = item.mediaMetadata.extras ?: return false
        val itemId = extras.getString(PlaylistBuilder.EXTRA_LIVE_ITEM_ID) ?: return false
        val station = extras.getString(PlaylistBuilder.EXTRA_STATION_NAME) ?: return false
        val oldUrl = item.localConfiguration?.uri?.toString() ?: return false
        if (!repairAttempts.add(itemId)) return false
        scope.launch {
            val newUrl = runCatching { StreamRepair.findMoved(oldUrl, station) }.getOrNull()
            if (newUrl == null || player.currentMediaItem?.mediaId != item.mediaId) {
                if (player.currentMediaItem?.mediaId == item.mediaId) skipAfterError(null)
                return@launch
            }
            player.replaceMediaItem(index, item.buildUpon().setUri(newUrl).build())
            player.prepare()
            player.play()
            ConfigRepository.get(this@PlaybackService).update { config ->
                config.copy(
                    tiles = config.tiles.map { tile ->
                        tile.copy(
                            items = tile.items.map {
                                if (it is PlayItem.Live && it.id == itemId) it.copy(streamUrl = newUrl) else it
                            },
                        )
                    },
                )
            }
            AppMessages.show(this@PlaybackService, "« $station » a changé d'adresse : tuile mise à jour.")
        }
        return true
    }

    /** Élément illisible : passe au suivant, ou arrête s'il n'y en a pas. */
    private fun skipAfterError(error: PlaybackException?) {
        val title = player.currentMediaItem?.mediaMetadata?.title ?: "élément"
        val reason = if (error == null || error.errorCode in 2000..2999) "réseau ou flux indisponible" else "format non lisible"
        if (player.hasNextMediaItem()) {
            AppMessages.show(this, "« $title » sauté : $reason.")
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        } else {
            AppMessages.show(this, "« $title » : lecture impossible ($reason).")
            stopAndClear()
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
            if (customCommand.customAction != CMD_PLAY_TILE) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val tile = args.getString(KEY_TILE_JSON)?.let {
                runCatching { ConfigJson.decodeFromString(Tile.serializer(), it) }.getOrNull()
            } ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            val day = args.getLong(KEY_EPOCH_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
                ?.let(java.time.LocalDate::ofEpochDay)

            val done = SettableFuture.create<SessionResult>()
            loadJob?.cancel()
            loadJob = scope.launch {
                try {
                    playTile(tile, day)
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

        /** Pause au-delà de laquelle l'enregistrement d'un direct est abandonné. */
        private const val MAX_BUFFER_PAUSE_MS = 30L * 60 * 1000

        /** Jour choisi dans le planning (LocalDate.toEpochDay), facultatif. */
        const val KEY_EPOCH_DAY = "epochDay"
    }
}

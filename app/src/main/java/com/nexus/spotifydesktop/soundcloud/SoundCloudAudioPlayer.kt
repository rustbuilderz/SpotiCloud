package com.nexus.spotifydesktop.soundcloud

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nexus.spotifydesktop.data.MediaSource
import com.nexus.spotifydesktop.data.NowPlaying
import com.nexus.spotifydesktop.data.ScTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** In-app SoundCloud playback (ExoPlayer progressive / HLS). */
class SoundCloudAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var current: ScTrack? = null

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        )
        .setAllowCrossProtocolRedirects(true)

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    emitState()
                    if (isPlaying) startProgress() else stopProgress()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    emitState()
                    if (playbackState == Player.STATE_ENDED) {
                        _ended.tryEmit(Unit)
                    }
                }
            })
        }

    private val _playerStates = MutableSharedFlow<NowPlaying>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    val playerStates: SharedFlow<NowPlaying> = _playerStates.asSharedFlow()

    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playbackEnded: SharedFlow<Unit> = _ended.asSharedFlow()

    val isActiveSource: Boolean
        get() = current != null

    fun play(track: ScTrack, streamUrl: String, positionMs: Long = 0L) {
        current = track
        val item = MediaItem.fromUri(streamUrl)
        player.setMediaItem(item)
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.playWhenReady = true
        emitState()
    }

    fun pause() {
        player.pause()
        emitState()
    }

    fun resume() {
        player.play()
        emitState()
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0L))
        emitState()
    }

    fun stop() {
        stopProgress()
        player.stop()
        player.clearMediaItems()
        current = null
    }

    fun release() {
        stopProgress()
        player.release()
        current = null
    }

    private fun startProgress() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                emitState()
                delay(500)
            }
        }
    }

    private fun stopProgress() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun emitState() {
        val track = current ?: return
        val np = NowPlaying(
            id = track.id.toString(),
            name = track.title,
            artists = track.artist,
            album = "SoundCloud",
            imageUrl = track.artworkUrl,
            uri = track.uri,
            isPlaying = player.isPlaying,
            progressMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = when {
                player.duration > 0 -> player.duration
                track.durationMs > 0 -> track.durationMs
                else -> 0L
            },
            shuffle = false,
            source = MediaSource.SoundCloud,
        )
        _playerStates.tryEmit(np)
    }
}

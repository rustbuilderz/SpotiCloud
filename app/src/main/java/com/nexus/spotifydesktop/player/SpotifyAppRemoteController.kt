package com.nexus.spotifydesktop.player

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.nexus.spotifydesktop.SpotifyConfig
import com.nexus.spotifydesktop.data.AppSecretsStore
import com.nexus.spotifydesktop.data.NowPlaying
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.ErrorCallback
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Controls playback in the installed Spotify app (Premium).
 *
 * Important: [connect] must use an [Activity] context — Application context often
 * "connects" without granting playback, so UI updates but there is no audio.
 */
class SpotifyAppRemoteController(
    private val app: Application,
    private val secrets: AppSecretsStore,
) {
    private val mutex = Mutex()
    private var remote: SpotifyAppRemote? = null
    private var playerSub: Subscription<PlayerState>? = null
    @Volatile private var hostActivity: Activity? = null

    private val _playerStates = MutableSharedFlow<NowPlaying>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    val playerStates: SharedFlow<NowPlaying> = _playerStates.asSharedFlow()

    val isConfigured: Boolean
        get() = !secrets.spotifyClientId.isNullOrBlank()

    val isConnected: Boolean
        get() = remote?.isConnected == true

    fun attachHost(activity: Activity) {
        hostActivity = activity
    }

    fun detachHost(activity: Activity) {
        if (hostActivity === activity) hostActivity = null
    }

    suspend fun ensureConnected(): SpotifyAppRemote = connect(showAuthView = true)

    /**
     * Try to read Spotify's current track without opening the Spotify UI.
     * Works when Spotify is already running in the background and we've been
     * authorized before. Returns null on timeout / not running / not authorized.
     */
    suspend fun trySilentPlayerState(): NowPlaying? {
        if (!isConfigured || !isSpotifyInstalled() || hostActivity == null) return null
        return withTimeoutOrNull(4_500L) {
            runCatching {
                val r = connect(showAuthView = false)
                startSubscription(r)
                awaitPlayerState(r).toNowPlaying().also { np ->
                    _playerStates.tryEmit(np)
                }
            }.getOrNull()
        }
    }

    private suspend fun connect(showAuthView: Boolean): SpotifyAppRemote = mutex.withLock {
        remote?.takeIf { it.isConnected }?.let { return it }
        val clientId = secrets.spotifyClientId
        if (clientId.isNullOrBlank()) {
            error("Add your Spotify Client ID in Setup / Settings")
        }
        if (!isSpotifyInstalled()) {
            error("Spotify app not installed")
        }
        val activity = hostActivity
            ?: error("Open the app screen first, then try play again")

        // Never startActivity(spotify:) — that jumps the user into Spotify.

        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val params = ConnectionParams.Builder(clientId)
                    .setRedirectUri(SpotifyConfig.REDIRECT_URI)
                    .showAuthView(showAuthView)
                    .build()
                SpotifyAppRemote.connect(
                    activity,
                    params,
                    object : Connector.ConnectionListener {
                        override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                            remote = spotifyAppRemote
                            Log.i(TAG, "App Remote connected (authView=$showAuthView)")
                            if (cont.isActive) cont.resume(spotifyAppRemote)
                        }

                        override fun onFailure(error: Throwable) {
                            remote = null
                            Log.e(TAG, "App Remote connect failed", error)
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    Exception(humanConnectError(error), error),
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    /**
     * Start a track and verify Spotify reports it as playing.
     * Optimistic UI alone was masking silent connect/play failures.
     */
    suspend fun playUri(uri: String) {
        require(uri.isNotBlank()) { "Empty track URI" }
        val r = ensureConnected()
        startSubscription(r)

        awaitEmpty { ok, err ->
            r.playerApi.play(uri).setResultCallback(ok).setErrorCallback(err)
        }

        // play() can succeed while Spotify stays paused / on another output
        repeat(6) { attempt ->
            delay(250)
            val state = awaitPlayerState(r)
            _playerStates.tryEmit(state.toNowPlaying())
            when {
                state.track != null && !state.isPaused -> return
                state.track != null && state.isPaused && attempt == 2 -> {
                    runCatching {
                        awaitEmpty { ok, err ->
                            r.playerApi.resume().setResultCallback(ok).setErrorCallback(err)
                        }
                    }
                }
            }
        }

        val final = awaitPlayerState(r)
        _playerStates.tryEmit(final.toNowPlaying())
        if (final.track == null) {
            error(
                "Spotify didn’t start the track. Check Premium, Dashboard package/SHA1, " +
                    "and that Spotify isn’t casting to another device.",
            )
        }
        if (final.isPaused) {
            error(
                "Spotify loaded the track but stayed paused — open Spotify once, " +
                    "press play there, then try again from this app.",
            )
        }
    }

    /** Append tracks so skip-next follows our list (not Spotify Radio / leftover shuffle). */
    suspend fun queueUris(uris: List<String>) {
        if (uris.isEmpty()) return
        val r = ensureConnected()
        for (uri in uris.take(40)) {
            if (uri.isBlank()) continue
            runCatching {
                awaitEmpty { ok, err ->
                    r.playerApi.queue(uri).setResultCallback(ok).setErrorCallback(err)
                }
            }
        }
    }

    suspend fun pause() {
        val r = ensureConnected()
        awaitEmpty { ok, err -> r.playerApi.pause().setResultCallback(ok).setErrorCallback(err) }
    }

    suspend fun resume() {
        val r = ensureConnected()
        awaitEmpty { ok, err -> r.playerApi.resume().setResultCallback(ok).setErrorCallback(err) }
    }

    suspend fun seekTo(positionMs: Long) {
        val r = ensureConnected()
        awaitEmpty { ok, err ->
            r.playerApi.seekTo(positionMs.coerceAtLeast(0L))
                .setResultCallback(ok)
                .setErrorCallback(err)
        }
    }

    /**
     * Resume the current Spotify track, or play [uri] and seek to [positionMs].
     * Used for "continue where you left off" without restarting at 0:00.
     */
    suspend fun resumeOrPlayAt(uri: String, positionMs: Long) {
        val r = ensureConnected()
        startSubscription(r)
        val state = runCatching { awaitPlayerState(r) }.getOrNull()
        val currentUri = state?.track?.uri
        if (currentUri == uri) {
            if (positionMs > 1_500L) {
                runCatching { seekTo(positionMs) }
            }
            awaitEmpty { ok, err -> r.playerApi.resume().setResultCallback(ok).setErrorCallback(err) }
        } else {
            playUri(uri)
            if (positionMs > 1_500L) {
                delay(350)
                runCatching { seekTo(positionMs) }
            }
        }
        delay(200)
        runCatching { _playerStates.tryEmit(awaitPlayerState(r).toNowPlaying()) }
    }

    suspend fun skipNext() {
        val r = ensureConnected()
        awaitEmpty { ok, err -> r.playerApi.skipNext().setResultCallback(ok).setErrorCallback(err) }
    }

    suspend fun skipPrevious() {
        val r = ensureConnected()
        awaitEmpty { ok, err -> r.playerApi.skipPrevious().setResultCallback(ok).setErrorCallback(err) }
    }

    /** Play a playlist/album context starting at [index] (0-based). Next/prev stay in that context. */
    suspend fun skipToIndex(contextUri: String, index: Int) {
        require(contextUri.isNotBlank()) { "Empty context URI" }
        val r = ensureConnected()
        startSubscription(r)
        awaitEmpty { ok, err ->
            r.playerApi.skipToIndex(contextUri, index.coerceAtLeast(0))
                .setResultCallback(ok)
                .setErrorCallback(err)
        }
        delay(300)
        val state = awaitPlayerState(r)
        _playerStates.tryEmit(state.toNowPlaying())
        if (state.track == null) {
            error("Couldn't start that playlist/album track")
        }
        if (state.isPaused) {
            runCatching {
                awaitEmpty { ok, err ->
                    r.playerApi.resume().setResultCallback(ok).setErrorCallback(err)
                }
            }
        }
    }

    suspend fun setShuffle(enabled: Boolean) {
        val r = ensureConnected()
        awaitEmpty { ok, err ->
            r.playerApi.setShuffle(enabled).setResultCallback(ok).setErrorCallback(err)
        }
    }

    fun disconnect() {
        runCatching { playerSub?.cancel() }
        playerSub = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
    }

    private fun isSpotifyInstalled(): Boolean {
        return try {
            app.packageManager.getPackageInfo("com.spotify.music", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun startSubscription(r: SpotifyAppRemote) {
        if (playerSub != null && remote === r) return
        runCatching { playerSub?.cancel() }
        playerSub = r.playerApi.subscribeToPlayerState().also { sub ->
            sub.setEventCallback { state ->
                _playerStates.tryEmit(state.toNowPlaying())
            }
            sub.setErrorCallback {
                playerSub = null
            }
        }
    }

    private suspend fun awaitPlayerState(r: SpotifyAppRemote): PlayerState =
        suspendCancellableCoroutine { cont ->
            r.playerApi.playerState
                .setResultCallback { state ->
                    if (cont.isActive) cont.resume(state)
                }
                .setErrorCallback { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private suspend fun awaitEmpty(
        block: (CallResult.ResultCallback<Empty>, ErrorCallback) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        block(
            CallResult.ResultCallback { if (cont.isActive) cont.resume(Unit) },
            ErrorCallback { e -> if (cont.isActive) cont.resumeWithException(e) },
        )
    }

    private fun humanConnectError(error: Throwable): String {
        val msg = (error.message ?: error.toString()).lowercase()
        return when {
            msg.contains("not installed") || msg.contains("spotify app") ->
                "Install / open the Spotify app, then try again"
            msg.contains("user_not_authorized") || msg.contains("authorization") ->
                "Allow this app in the Spotify permission prompt"
            msg.contains("offline") ->
                "Spotify reports offline — open Spotify on Wi‑Fi once"
            else ->
                "Can't connect to Spotify: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun PlayerState.toNowPlaying(): NowPlaying {
        val t = track
        val artists = t?.artists?.joinToString(", ") { it.name }.orEmpty()
        val image = t?.imageUri?.raw
            ?.removePrefix("spotify:image:")
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://i.scdn.co/image/$it" }
        return NowPlaying(
            id = t?.uri?.substringAfterLast(':'),
            name = t?.name ?: "Nothing playing",
            artists = artists,
            album = t?.album?.name.orEmpty(),
            imageUrl = image,
            uri = t?.uri,
            progressMs = playbackPosition,
            durationMs = t?.duration ?: 0L,
            isPlaying = !isPaused && t != null,
            shuffle = playbackOptions?.isShuffling == true,
            source = com.nexus.spotifydesktop.data.MediaSource.Spotify,
        )
    }

    companion object {
        private const val TAG = "SpotifyAppRemote"
    }
}

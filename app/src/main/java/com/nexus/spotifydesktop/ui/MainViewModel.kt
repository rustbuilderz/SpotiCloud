package com.nexus.spotifydesktop.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.spotifydesktop.BuildConfig
import com.nexus.spotifydesktop.auth.DevOAuth
import com.nexus.spotifydesktop.auth.Pkce
import com.nexus.spotifydesktop.auth.TokenExchanger
import com.nexus.spotifydesktop.auth.TokenStore
import android.os.Build
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.nexus.spotifydesktop.api.LyricsClient
import com.nexus.spotifydesktop.api.SpotifyApiClient
import com.nexus.spotifydesktop.data.AppSecretsStore
import com.nexus.spotifydesktop.data.ConsoleLevel
import com.nexus.spotifydesktop.data.ConsoleLine
import com.nexus.spotifydesktop.data.LibraryItem
import com.nexus.spotifydesktop.data.LikedLibraryCache
import com.nexus.spotifydesktop.data.LyricLine
import com.nexus.spotifydesktop.data.MediaSource
import com.nexus.spotifydesktop.data.NowPlaying
import com.nexus.spotifydesktop.data.RecentTrack
import com.nexus.spotifydesktop.data.ScBrowseSection
import com.nexus.spotifydesktop.data.ScPlaylist
import com.nexus.spotifydesktop.data.ScTrack
import com.nexus.spotifydesktop.data.SpotifyLibraryCache
import com.nexus.spotifydesktop.data.TrackItem
import com.nexus.spotifydesktop.player.PlaybackCache
import com.nexus.spotifydesktop.player.SpotifyAppRemoteController
import com.nexus.spotifydesktop.player.SystemVolumeController
import com.nexus.spotifydesktop.soundcloud.ScLikeMode
import com.nexus.spotifydesktop.soundcloud.SoundCloudApiClient
import com.nexus.spotifydesktop.soundcloud.SoundCloudAudioPlayer
import com.nexus.spotifydesktop.soundcloud.SoundCloudCaptchaNeeded
import com.nexus.spotifydesktop.soundcloud.SoundCloudTokenStore
import java.util.concurrent.atomic.AtomicLong

data class UiState(
    val authed: Boolean = false,
    val showWebLogin: Boolean = false,
    val userName: String = "",
    val library: List<LibraryItem> = emptyList(),
    val libraryLoading: Boolean = false,
    val selectedId: String = "liked",
    val detailTitle: String = "",
    val detailSubtitle: String = "",
    val tracks: List<TrackItem> = emptyList(),
    val tracksLoading: Boolean = false,
    val tracksRefreshing: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<TrackItem> = emptyList(),
    val searchArtists: List<com.nexus.spotifydesktop.data.SearchArtist> = emptyList(),
    val searchAlbums: List<com.nexus.spotifydesktop.data.SearchAlbum> = emptyList(),
    val searching: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val shuffle: Boolean = false,
    val volume: Float = 0.7f,
    val error: String? = null,
    val status: String = "",
    val trackLiked: Boolean = false,
    val lyrics: List<LyricLine> = emptyList(),
    val lyricsLoading: Boolean = false,
    val lyricsError: String? = null,
    val recents: List<RecentTrack> = emptyList(),
    val scAuthed: Boolean = false,
    val scUserName: String = "",
    val scClientId: String = "",
    val scSection: ScBrowseSection = ScBrowseSection.Hub,
    val scTracks: List<ScTrack> = emptyList(),
    val scPlaylists: List<ScPlaylist> = emptyList(),
    val scPlaylistTitle: String = "",
    val scSearchQuery: String = "",
    val scSearchUsers: List<com.nexus.spotifydesktop.data.ScSearchUser> = emptyList(),
    val scSearchAlbums: List<ScPlaylist> = emptyList(),
    val scSearching: Boolean = false,
    val scLoading: Boolean = false,
    val scError: String? = null,
    val scStatus: String = "",
    val showScWebLogin: Boolean = false,
    val scCaptchaUrl: String? = null,
    val scLikeMode: ScLikeMode = ScLikeMode.WebView,
    val scTrackLikeUrl: String? = null,
    val scTrackLikeName: String = "",
    val consoleLines: List<ConsoleLine> = emptyList(),
    val needsSetup: Boolean = false,
    val setupStep: SetupStep = SetupStep.Welcome,
    val spotifyClientId: String = "",
    val spotifyClientSecret: String = "",
)

/** Web session for library/search/likes; App Remote for Spotify playback. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val tokenStore = TokenStore(app)
    private val secrets = AppSecretsStore(app)
    private val api = SpotifyApiClient(tokenStore, app)
    private val scStore = SoundCloudTokenStore(app)
    private val scApi = SoundCloudApiClient(scStore, app)
    private val scPlayer = SoundCloudAudioPlayer(app)
    private val appRemote = SpotifyAppRemoteController(app, secrets)
    private val playbackCache = PlaybackCache(app)
    private val likedCache = LikedLibraryCache(app)
    private val libraryCache = SpotifyLibraryCache(app)
    private val lyricsClient = LyricsClient()
    private val systemVolume = SystemVolumeController(app)
    private var unregisterVolumeListener: (() -> Unit)? = null
    private var loadGen = 0
    private var playQueue: List<TrackItem> = emptyList()
    private var queueIndex: Int = 0
    private var scQueue: List<ScTrack> = emptyList()
    private var scQueueIndex: Int = 0
    private val scLikedIds = mutableSetOf<Long>()
    private var homeSpotifyLikes: List<RecentTrack> = emptyList()
    private var homeSoundCloudLikes: List<RecentTrack> = emptyList()
    private var homeScTracksCache: List<ScTrack> = emptyList()
    // Armed only on Spotify tab / Liked / search — avoids boot 429s.
    private var spotifyNetworkArmed = false
    private var libraryNetworkJob: Job? = null
    private var pendingScLike: PendingScLike? = null

    private data class PendingScLike(
        val trackId: Long,
        val permalink: String?,
        val wantLiked: Boolean,
        val trackName: String,
    )
    // Context URI for App Remote skipToIndex; null for Liked/search/shuffle.
    private var spotifyContextUri: String? = null
    private var progressJob: Job? = null
    private var pendingPlaybackUri: String? = null
    private var pendingUntilMs: Long = 0L
    private var likeJob: Job? = null
    private var lyricsJob: Job? = null
    private var lastLyricsKey: String? = null
    private var lastLikedUri: String? = null
    private var lastCacheSaveMs: Long = 0L
    private val consoleSeq = AtomicLong(0L)

    private val cachedBoot = playbackCache.load()
    private val _state = MutableStateFlow(
        UiState(
            authed = tokenStore.hasSession,
            userName = tokenStore.userName.orEmpty(),
            volume = systemVolume.getNormalized(),
            nowPlaying = cachedBoot,
            shuffle = cachedBoot?.shuffle == true,
            status = cachedBoot?.let { "Continue · ${it.name}" }.orEmpty(),
            recents = emptyList(),
            scAuthed = scStore.hasSession,
            scUserName = scStore.userName.orEmpty(),
            scClientId = scStore.clientId.orEmpty(),
            scLikeMode = scStore.likeMode,
            needsSetup = computeNeedsSetup(),
            setupStep = when {
                !secrets.setupAgreed -> SetupStep.Welcome
                !tokenStore.hasSession || !secrets.hasSpotifyPlaybackCreds -> SetupStep.Spotify
                !scStore.hasSession -> SetupStep.SoundCloud
                else -> SetupStep.Welcome
            },
            spotifyClientId = secrets.spotifyClientId.orEmpty(),
            spotifyClientSecret = secrets.spotifyClientSecret.orEmpty(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun computeNeedsSetup(): Boolean {
        // Existing installs already signed into both — skip wizard once.
        if (!secrets.setupComplete &&
            tokenStore.hasSession &&
            scStore.hasSession &&
            secrets.hasSpotifyPlaybackCreds
        ) {
            secrets.setupComplete = true
            secrets.setupAgreed = true
            return false
        }
        if (secrets.setupComplete &&
            tokenStore.hasSession &&
            scStore.hasSession &&
            secrets.hasSpotifyPlaybackCreds
        ) {
            return false
        }
        return !secrets.setupComplete ||
            !tokenStore.hasSession ||
            !scStore.hasSession ||
            !secrets.hasSpotifyPlaybackCreds
    }

    fun agreeAndStartSetup() {
        secrets.setupAgreed = true
        _state.update {
            it.copy(
                setupStep = SetupStep.Spotify,
                needsSetup = true,
                error = null,
            )
        }
    }

    fun saveSpotifyPlaybackCreds(clientId: String, clientSecret: String) {
        secrets.spotifyClientId = clientId.trim().ifBlank { null }
        secrets.spotifyClientSecret = clientSecret.trim().ifBlank { null }
        _state.update {
            it.copy(
                spotifyClientId = secrets.spotifyClientId.orEmpty(),
                spotifyClientSecret = secrets.spotifyClientSecret.orEmpty(),
                status = if (secrets.hasSpotifyPlaybackCreds) {
                    "Spotify Client ID + Secret saved"
                } else {
                    "Client ID and Secret are both required"
                },
                error = if (secrets.hasSpotifyPlaybackCreds) {
                    null
                } else {
                    "Client ID and Client Secret are both required for playback"
                },
            )
        }
        console(
            ConsoleLevel.Ok,
            "Spotify",
            if (secrets.hasSpotifyPlaybackCreds) {
                "Playback creds saved on device"
            } else {
                "Playback creds incomplete"
            },
        )
    }

    /** @deprecated Prefer [saveSpotifyPlaybackCreds] */
    fun saveSpotifyClientId(raw: String) {
        saveSpotifyPlaybackCreds(raw, secrets.spotifyClientSecret.orEmpty())
    }

    fun continueSetupFromSpotify() {
        if (!tokenStore.hasSession) {
            _state.update { it.copy(error = "Sign in with Spotify first") }
            return
        }
        if (!secrets.hasSpotifyPlaybackCreds) {
            _state.update {
                it.copy(error = "Client ID and Client Secret are required for Spotify playback")
            }
            return
        }
        _state.update {
            it.copy(setupStep = SetupStep.SoundCloud, error = null, scError = null)
        }
    }

    fun finishSetup() {
        if (!tokenStore.hasSession) {
            _state.update { it.copy(error = "Spotify login required", setupStep = SetupStep.Spotify) }
            return
        }
        if (!secrets.hasSpotifyPlaybackCreds) {
            _state.update {
                it.copy(
                    error = "Client ID and Client Secret are required for playback",
                    setupStep = SetupStep.Spotify,
                )
            }
            return
        }
        if (!scStore.hasSession) {
            _state.update {
                it.copy(scError = "SoundCloud OAuth + client_id required", setupStep = SetupStep.SoundCloud)
            }
            return
        }
        secrets.setupComplete = true
        secrets.setupAgreed = true
        _state.update {
            it.copy(
                needsSetup = false,
                error = null,
                scError = null,
                status = "Setup complete",
            )
        }
        console(ConsoleLevel.Ok, "App", "Setup complete")
        softStartSpotify()
    }

    fun startResetup() {
        stopProgressTick()
        runCatching { appRemote.disconnect() }
        runCatching { scPlayer.stop() }
        tokenStore.clear()
        scStore.clear()
        scLikedIds.clear()
        homeSpotifyLikes = emptyList()
        homeSoundCloudLikes = emptyList()
        homeScTracksCache = emptyList()
        playQueue = emptyList()
        scQueue = emptyList()
        spotifyNetworkArmed = false
        api.invalidateClientToken()
        api.clearRateLimit()
        secrets.clearSetupFlags()
        // Keep Spotify Dashboard Client ID + Secret — not a login; clear sessions only.
        _state.value = UiState(
            volume = systemVolume.getNormalized(),
            needsSetup = true,
            setupStep = SetupStep.Welcome,
            spotifyClientId = secrets.spotifyClientId.orEmpty(),
            spotifyClientSecret = secrets.spotifyClientSecret.orEmpty(),
            scLikeMode = scStore.likeMode,
            status = "Re-setup · sign in again",
        )
        console(ConsoleLevel.Warn, "App", "Re-setup · cleared Spotify + SoundCloud logins")
    }

    private fun refreshNeedsSetup() {
        _state.update {
            it.copy(
                needsSetup = computeNeedsSetup(),
                spotifyClientId = secrets.spotifyClientId.orEmpty(),
                spotifyClientSecret = secrets.spotifyClientSecret.orEmpty(),
                scAuthed = scStore.hasSession,
                scClientId = scStore.clientId.orEmpty(),
                scUserName = scStore.userName.orEmpty(),
                authed = tokenStore.hasSession,
                userName = tokenStore.userName.orEmpty(),
            )
        }
    }

    init {
        api.onLog = { level, tag, message -> console(level, tag, message) }
        scApi.onLog = { level, tag, message -> console(level, tag, message) }
        console(ConsoleLevel.Info, "App", "Console ready")
        // Instant UI from disk cache (no API)
        likedCache.loadSpotify()?.let { snap ->
            setHomeLikedPreview(snap.tracks)
            console(ConsoleLevel.Ok, "Cache", "Spotify liked · ${snap.tracks.size} from disk")
        }
        libraryCache.load()?.let { lib ->
            _state.update {
                it.copy(
                    library = lib,
                    libraryLoading = false,
                    status = "Library · cached",
                )
            }
            console(ConsoleLevel.Ok, "Cache", "Spotify library · ${lib.size} from disk")
        }
        likedCache.loadSoundCloud()?.let { snap ->
            scLikedIds.clear()
            scLikedIds.addAll(snap.tracks.map { it.id })
            applyHomeScLikes(snap.tracks)
            console(ConsoleLevel.Ok, "Cache", "SoundCloud liked · ${snap.tracks.size} from disk")
        }
        if (tokenStore.hasSession) {
            console(ConsoleLevel.Info, "Spotify", "Session found · warming up")
        } else {
            console(ConsoleLevel.Warn, "Spotify", "Not signed in")
        }
        if (scStore.hasSession) {
            console(ConsoleLevel.Info, "SoundCloud", "Session found · ${scStore.userName ?: "user"}")
        }
        // Live player updates from App Remote (no Connect Web API polling)
        viewModelScope.launch {
            appRemote.playerStates.collect { np -> applyPlayerState(np) }
        }
        viewModelScope.launch {
            scPlayer.playerStates.collect { np ->
                val prevUri = _state.value.nowPlaying?.uri
                _state.update {
                    it.copy(
                        nowPlaying = np,
                        status = if (np.isPlaying) "Playing · ${np.name}" else "Paused · ${np.name}",
                        error = null,
                    )
                }
                persistPlayback(np)
                if (np.uri != prevUri) refreshLikeAndLyrics(np)
            }
        }
        viewModelScope.launch {
            scPlayer.playbackEnded.collect {
                advanceScQueue(+1)
            }
        }
        if (_state.value.authed) softStartSpotify()
        if (scStore.hasSession) {
            viewModelScope.launch {
                runCatching { scApi.ensureClientId() }
                runCatching { scApi.getMe() }
                    .onSuccess { (_, name) ->
                        _state.update {
                            it.copy(
                                scAuthed = true,
                                scUserName = name,
                                scClientId = scStore.clientId.orEmpty(),
                            )
                        }
                        refreshScLikedIdsFromCache()
                        refreshHomeScLikes()
                        viewModelScope.launch { runCatching { scApi.warmWebSession() } }
                    }
                    .onFailure { e ->
                        _state.update { s -> s.copy(scError = e.message) }
                        consoleAuto("SoundCloud", e.message ?: "Session check failed", isError = true)
                    }
            }
        } else {
            viewModelScope.launch {
                runCatching { scApi.ensureClientId() }
                    .onSuccess { id -> _state.update { it.copy(scClientId = id) } }
            }
        }
        unregisterVolumeListener = systemVolume.registerVolumeKeys { level ->
            _state.update { it.copy(volume = level) }
        }
    }

    override fun onCleared() {
        playbackCache.save(_state.value.nowPlaying)
        unregisterVolumeListener?.invoke()
        unregisterVolumeListener = null
        stopProgressTick()
        scPlayer.release()
        appRemote.disconnect()
        super.onCleared()
    }

    fun attachPlayerHost(activity: android.app.Activity) {
        // Remember Activity for play; silently sync Spotify state if already running
        // (no startActivity — won't jump you into Spotify).
        appRemote.attachHost(activity)
        scApi.attachWebHost(activity)
        if (tokenStore.hasSession) {
            viewModelScope.launch { syncPlaybackQuietly() }
        }
    }

    fun detachPlayerHost(activity: android.app.Activity) {
        playbackCache.save(_state.value.nowPlaying)
        appRemote.detachHost(activity)
    }

    // Silent App Remote sync; falls back to disk cache.
    private suspend fun syncPlaybackQuietly() {
        val live = appRemote.trySilentPlayerState()
        if (live != null && !live.uri.isNullOrBlank() && live.name.isNotBlank() &&
            live.name != "Nothing playing"
        ) {
            applyPlayerState(live)
            playbackCache.save(live)
            _state.update {
                it.copy(
                    status = if (live.isPlaying) "Playing · ${live.name}" else "Paused · ${live.name}",
                    error = null,
                )
            }
            return
        }
        val cached = _state.value.nowPlaying ?: playbackCache.load()
        if (cached != null) {
            _state.update {
                it.copy(
                    nowPlaying = cached.copy(isPlaying = false),
                    status = "Continue · ${cached.name}",
                )
            }
        }
    }

    fun startWebLogin() {
        _state.update { it.copy(showWebLogin = true, error = null) }
    }

    fun cancelWebLogin() {
        _state.update { it.copy(showWebLogin = false) }
    }

    fun onWebSessionCaptured(session: com.nexus.spotifydesktop.auth.WebSessionCookies) {
        tokenStore.spDc = session.spDc
        tokenStore.spT = session.spT
        tokenStore.accessToken = null
        tokenStore.webClientId = null
        tokenStore.clientVersion = null
        tokenStore.expiresAtMs = 0L
        api.clearRateLimit()
        api.invalidateClientToken()
        _state.update {
            it.copy(
                showWebLogin = false,
                authed = true,
                error = null,
                status = "Spotify signed in",
                userName = tokenStore.userName.orEmpty(),
                libraryLoading = false,
            )
        }
        refreshNeedsSetup()
        // During setup / re-setup: don't block on WebView library sync (was hanging 60s
        // after invalidate). Soft-start only; user can Refresh library later.
        if (_state.value.needsSetup) {
            softStartSpotify()
            console(ConsoleLevel.Ok, "Spotify", "Signed in · continue setup")
        } else {
            refreshSession(forceNetwork = true)
        }
    }

    fun onAuthError(message: String) {
        _state.update { it.copy(error = message, showWebLogin = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun logout() {
        stopProgressTick()
        appRemote.disconnect()
        playbackCache.clear()
        tokenStore.clear()
        _state.value = UiState(
            volume = systemVolume.getNormalized(),
            scAuthed = scStore.hasSession,
            scUserName = scStore.userName.orEmpty(),
            scClientId = scStore.clientId.orEmpty(),
            scLikeMode = scStore.likeMode,
            needsSetup = true,
            setupStep = SetupStep.Spotify,
            spotifyClientId = secrets.spotifyClientId.orEmpty(),
            spotifyClientSecret = secrets.spotifyClientSecret.orEmpty(),
        )
        secrets.setupComplete = false
    }

    fun clearAppCache() {
        playbackCache.clear()
        likedCache.clear()
        libraryCache.clear()
        homeSpotifyLikes = emptyList()
        homeSoundCloudLikes = emptyList()
        homeScTracksCache = emptyList()
        spotifyNetworkArmed = false
        api.invalidateClientToken()
        api.clearRateLimit()
        _state.update {
            it.copy(
                status = "Cache cleared",
                error = null,
                recents = emptyList(),
                library = listOf(LibraryItem.Liked),
            )
        }
        console(ConsoleLevel.Info, "App", "Cache cleared (playback + liked + library)")
        // Wait for Spotify tab / refresh before hitting APIs again
    }

    fun systemDetails(): List<Pair<String, String>> {
        val appCtx = getApplication<Application>()
        return listOf(
            "App" to "${appCtx.packageName}",
            "Version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Spotify Client ID" to if (secrets.spotifyClientId != null) "Saved on device" else "Not set",
            "Spotify Client Secret" to if (secrets.spotifyClientSecret != null) "Saved on device" else "Not set",
            "Spotify session" to if (tokenStore.hasSession) "Signed in" else "Signed out",
            "User" to (tokenStore.userName ?: "—"),
            "App Remote" to if (appRemote.isConfigured) {
                if (appRemote.isConnected) "Connected" else "Configured"
            } else {
                "Missing client ID"
            },
            "Home likes" to run {
                val sp = _state.value.recents.count { it.source == MediaSource.Spotify }
                val sc = _state.value.recents.count { it.source == MediaSource.SoundCloud }
                "$sp Spotify + $sc SoundCloud"
            },
            "SoundCloud" to if (scStore.hasSession) {
                scStore.userName ?: "Signed in"
            } else {
                "Signed out"
            },
            "SC like mode" to when (scStore.likeMode) {
                ScLikeMode.WebView -> "Open track (safer)"
                ScLikeMode.Api -> "API"
            },
        )
    }

    fun startScWebLogin() {
        _state.update { it.copy(showScWebLogin = true, scError = null) }
    }

    fun cancelScWebLogin() {
        _state.update { it.copy(showScWebLogin = false) }
    }

    fun onScWebLoginCaptured(oauthToken: String, clientId: String?) {
        _state.update { it.copy(showScWebLogin = false) }
        connectSoundCloud(oauthToken, clientId)
    }

    fun connectSoundCloudManual(oauthToken: String, clientId: String?) {
        val cid = clientId?.trim().orEmpty()
        if (oauthToken.isBlank()) {
            _state.update { it.copy(scError = "OAuth token is required") }
            return
        }
        if (cid.isBlank()) {
            _state.update { it.copy(scError = "client_id is required") }
            return
        }
        connectSoundCloud(oauthToken, cid)
    }

    private fun connectSoundCloud(oauthToken: String, clientId: String?) {
        viewModelScope.launch {
            _state.update { it.copy(scLoading = true, scError = null, scStatus = "Connecting…") }
            try {
                if (!clientId.isNullOrBlank()) scStore.clientId = clientId
                val (_, name) = scApi.connect(oauthToken, clientId)
                _state.update {
                    it.copy(
                        scAuthed = true,
                        scUserName = name,
                        scClientId = scStore.clientId.orEmpty(),
                        scLoading = false,
                        scStatus = "Connected · $name",
                        scError = null,
                    )
                }
                refreshScLikedIdsFromCache()
                refreshHomeScLikes(forceNetwork = true)
                console(ConsoleLevel.Ok, "SoundCloud", "Connected · $name")
                viewModelScope.launch { runCatching { scApi.warmWebSession() } }
                refreshNeedsSetup()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        scLoading = false,
                        scAuthed = false,
                        scError = e.message ?: "SoundCloud connect failed",
                        scStatus = "Error",
                    )
                }
                consoleAuto("SoundCloud", e.message ?: "Connect failed", isError = true)
            }
        }
    }

    fun disconnectSoundCloud() {
        scStore.clear()
        scLikedIds.clear()
        homeSoundCloudLikes = emptyList()
        homeScTracksCache = emptyList()
        likedCache.clearSoundCloud()
        publishHomeMergedLikes()
        console(ConsoleLevel.Warn, "SoundCloud", "Disconnected")
        _state.update {
            it.copy(
                scAuthed = false,
                scUserName = "",
                scClientId = "",
                scSection = ScBrowseSection.Hub,
                scTracks = emptyList(),
                scPlaylists = emptyList(),
                scSearchQuery = "",
                scStatus = "Disconnected",
                scError = null,
                trackLiked = if (it.nowPlaying?.source == MediaSource.SoundCloud) false else it.trackLiked,
            )
        }
        viewModelScope.launch {
            runCatching { scApi.ensureClientId(force = true) }
                .onSuccess { id -> _state.update { it.copy(scClientId = id) } }
        }
    }

    // Hearts from disk only.
    private fun refreshScLikedIdsFromCache() {
        likedCache.loadSoundCloud()?.let { snap ->
            scLikedIds.clear()
            scLikedIds.addAll(snap.tracks.map { it.id })
            val np = _state.value.nowPlaying
            if (np?.source == MediaSource.SoundCloud) {
                val tid = np.id?.toLongOrNull()
                if (tid != null) {
                    _state.update { it.copy(trackLiked = tid in scLikedIds) }
                }
            }
        }
    }

    fun refreshScClientId() {
        viewModelScope.launch {
            _state.update { it.copy(scStatus = "Fetching client_id…", scError = null) }
            runCatching { scApi.ensureClientId(force = true) }
                .onSuccess { id ->
                    _state.update {
                        it.copy(scClientId = id, scStatus = "client_id ready")
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(scError = e.message, scStatus = "Error") }
                }
        }
    }

    fun openScSection(section: ScBrowseSection) {
        if (!scStore.hasSession && section != ScBrowseSection.Hub) {
            _state.update { it.copy(scError = "Connect SoundCloud in Settings first") }
            return
        }
        _state.update {
            it.copy(
                scSection = section,
                scError = null,
                scPlaylistTitle = "",
            )
        }
        when (section) {
            ScBrowseSection.Feed -> loadScFeed()
            ScBrowseSection.Liked -> loadScLiked()
            ScBrowseSection.Playlists -> loadScPlaylists()
            ScBrowseSection.Hub, ScBrowseSection.PlaylistTracks, ScBrowseSection.ArtistTracks, ScBrowseSection.Search -> Unit
        }
    }

    fun scBack() {
        val cur = _state.value.scSection
        _state.update {
            it.copy(
                scSection = when (cur) {
                    ScBrowseSection.PlaylistTracks -> ScBrowseSection.Playlists
                    ScBrowseSection.ArtistTracks -> ScBrowseSection.Hub
                    ScBrowseSection.Search -> ScBrowseSection.Hub
                    else -> ScBrowseSection.Hub
                },
                scError = null,
            )
        }
    }

    fun loadScFeed() {
        viewModelScope.launch {
            _state.update { it.copy(scLoading = true, scError = null, scStatus = "Loading feed…") }
            runCatching { scApi.getFeed(40) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            scTracks = page.items,
                            scLoading = false,
                            scStatus = "Feed · ${page.items.size}",
                            scSection = ScBrowseSection.Feed,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(scLoading = false, scError = e.message, scStatus = "Error")
                    }
                }
        }
    }

    fun loadScLiked() {
        viewModelScope.launch {
            _state.update { it.copy(scLoading = true, scError = null, scStatus = "Loading likes…") }
            // Instant from cache
            likedCache.loadSoundCloud()?.let { snap ->
                scLikedIds.addAll(snap.tracks.map { it.id })
                _state.update {
                    it.copy(
                        scTracks = snap.tracks,
                        scLoading = false,
                        scStatus = "Liked · cached ${snap.tracks.size}",
                        scSection = ScBrowseSection.Liked,
                    )
                }
                applyHomeScLikes(snap.tracks)
            }
            syncSoundCloudLikes(updateTab = true)
        }
    }

    fun loadScPlaylists() {
        viewModelScope.launch {
            _state.update { it.copy(scLoading = true, scError = null, scStatus = "Loading playlists…") }
            runCatching { scApi.getPlaylists(40) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            scPlaylists = page.items,
                            scLoading = false,
                            scStatus = "Playlists · ${page.items.size}",
                            scSection = ScBrowseSection.Playlists,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(scLoading = false, scError = e.message, scStatus = "Error")
                    }
                }
        }
    }

    fun openScPlaylist(playlist: ScPlaylist) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    scLoading = true,
                    scError = null,
                    scStatus = "Loading ${playlist.title}…",
                    scPlaylistTitle = playlist.title,
                    scSection = ScBrowseSection.PlaylistTracks,
                )
            }
            runCatching { scApi.getPlaylist(playlist.id) }
                .onSuccess { (_, tracks) ->
                    _state.update {
                        it.copy(
                            scTracks = tracks,
                            scLoading = false,
                            scStatus = "${playlist.title} · ${tracks.size}",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(scLoading = false, scError = e.message, scStatus = "Error")
                    }
                }
        }
    }

    fun openScArtist(userId: Long?, artistName: String, permalinkUrl: String? = null) {
        val name = artistName.trim()
        if ((userId == null || userId <= 0L) && name.isBlank() && permalinkUrl.isNullOrBlank()) return
        if (!scStore.hasSession) {
            _state.update {
                it.copy(scError = "Connect SoundCloud in Settings first")
            }
            return
        }
        viewModelScope.launch {
            val label = name.ifBlank { "Artist" }
            console(ConsoleLevel.Info, "SoundCloud", "Artist · $label")
            _state.update {
                it.copy(
                    scLoading = true,
                    scError = null,
                    scStatus = "Loading $label…",
                    scPlaylistTitle = label,
                    scSection = ScBrowseSection.ArtistTracks,
                    scTracks = emptyList(),
                )
            }
            runCatching {
                val uid = scApi.resolveUserId(userId, name, permalinkUrl)
                scApi.getUserTracks(uid, limit = 50)
            }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            scTracks = page.items,
                            scLoading = false,
                            scStatus = "$label · ${page.items.size} recent",
                        )
                    }
                    console(ConsoleLevel.Ok, "SoundCloud", "Artist · ${page.items.size} tracks")
                }
                .onFailure { e ->
                    consoleAuto("SoundCloud", e.message ?: "Artist load failed", isError = true)
                    _state.update {
                        it.copy(scLoading = false, scError = e.message, scStatus = "Error")
                    }
                }
        }
    }

    fun openSpotifyArtist(artistId: String?, artistName: String) {
        val name = artistName.substringBefore(',').trim().ifBlank { artistName.trim() }
        if (name.isBlank() && artistId.isNullOrBlank()) return
        val idKey = artistId?.takeIf { it.isNotBlank() } ?: "name:${name.lowercase()}"
        loadSelection(
            id = "artist:$idKey",
            titleOverride = name.ifBlank { "Artist" },
            subtitleOverride = "Popular",
            artistNameForFetch = name,
            artistIdForFetch = artistId,
        )
    }

    fun searchSoundCloud(query: String) {
        _state.update { it.copy(scSearchQuery = query) }
        if (query.isBlank()) {
            _state.update {
                it.copy(
                    scSearchUsers = emptyList(),
                    scSearchAlbums = emptyList(),
                    scSearching = false,
                    scTracks = if (it.scSection == ScBrowseSection.Search) emptyList() else it.scTracks,
                    scSection = if (it.scSection == ScBrowseSection.Search) ScBrowseSection.Hub else it.scSection,
                )
            }
            return
        }
        if (!scStore.hasSession) {
            _state.update { it.copy(scError = "Connect SoundCloud in Settings to search") }
            return
        }
        viewModelScope.launch {
            delay(280)
            if (_state.value.scSearchQuery != query) return@launch
            _state.update {
                it.copy(
                    scSearching = true,
                    scLoading = true,
                    scError = null,
                    scStatus = "Searching…",
                )
            }
            runCatching { scApi.searchAll(query) }
                .onSuccess { bundle ->
                    if (_state.value.scSearchQuery != query) return@onSuccess
                    _state.update {
                        it.copy(
                            scTracks = bundle.tracks,
                            scSearchUsers = bundle.users,
                            scSearchAlbums = bundle.albums,
                            scSearching = false,
                            scLoading = false,
                            scSection = ScBrowseSection.Search,
                            scStatus = "Search · ${bundle.tracks.size} songs · ${bundle.users.size} people · ${bundle.albums.size} albums",
                        )
                    }
                    console(
                        ConsoleLevel.Ok,
                        "SoundCloud",
                        "Search · ${bundle.tracks.size} songs · ${bundle.users.size} people · ${bundle.albums.size} albums",
                    )
                }
                .onFailure { e ->
                    consoleAuto("SoundCloud", e.message ?: "Search failed", isError = true)
                    _state.update {
                        it.copy(
                            scSearching = false,
                            scLoading = false,
                            scError = e.message,
                            scStatus = "Error",
                        )
                    }
                }
        }
    }

    fun toggleScLike(track: ScTrack) {
        viewModelScope.launch {
            val liked = track.id in scLikedIds
            runCatching {
                if (liked) {
                    scApi.unlikeTrack(track.id, track.permalinkUrl)
                } else {
                    scApi.likeTrack(track.id, track.permalinkUrl)
                }
            }
                .onSuccess {
                    if (liked) scLikedIds.remove(track.id) else scLikedIds.add(track.id)
                    _state.update {
                        it.copy(
                            scStatus = if (liked) "Unliked · ${track.title}" else "Liked · ${track.title}",
                            trackLiked = if (_state.value.nowPlaying?.id == track.id.toString()) !liked else it.trackLiked,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(scError = e.message) }
                }
        }
    }

    fun playScTrack(track: ScTrack, list: List<ScTrack> = listOf(track)) {
        viewModelScope.launch {
            try {
                runCatching { appRemote.pause() }
                scQueue = if (list.isNotEmpty()) list else listOf(track)
                scQueueIndex = scQueue.indexOfFirst { it.id == track.id }.let { if (it >= 0) it else 0 }
                _state.update {
                    it.copy(
                        status = "Loading “${track.title}”…",
                        nowPlaying = track.toNowPlaying(isPlaying = false),
                        error = null,
                    )
                }
                val url = scApi.resolveStreamUrl(track)
                scPlayer.play(track, url)
                // Seed heart immediately from cache; refreshLikeAndLyrics also runs on uri change
                _state.update {
                    it.copy(
                        status = "Playing · ${track.title} (${scQueueIndex + 1}/${scQueue.size})",
                        error = null,
                        trackLiked = track.id in scLikedIds,
                    )
                }
                lastLikedUri = null // force refreshLikeAndLyrics to re-check
                refreshLikeAndLyrics(track.toNowPlaying(isPlaying = true))
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message ?: "SoundCloud playback failed",
                        status = "Can't play",
                        nowPlaying = it.nowPlaying?.copy(isPlaying = false),
                    )
                }
            }
        }
    }

    private fun ScTrack.toNowPlaying(isPlaying: Boolean) = NowPlaying(
        id = id.toString(),
        name = title,
        artists = artist,
        album = "SoundCloud",
        imageUrl = artworkUrl,
        uri = uri,
        isPlaying = isPlaying,
        progressMs = 0L,
        durationMs = durationMs,
        shuffle = false,
        source = MediaSource.SoundCloud,
    )

    private fun advanceScQueue(delta: Int) {
        if (scQueue.isEmpty()) return
        val newIdx = scQueueIndex + delta
        if (newIdx !in scQueue.indices) {
            _state.update {
                it.copy(status = if (delta > 0) "End of SoundCloud queue" else "Start of queue")
            }
            return
        }
        playScTrack(scQueue[newIdx], scQueue)
    }

    private fun isAuthFailure(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("session expired") ||
            m.contains("not logged in") ||
            m.contains("sp_dc invalid") ||
            m.contains("log in again")
    }

    private fun softFail(msg: String) {
        consoleAuto("Spotify", msg, isError = true)
        _state.update {
            it.copy(
                library = it.library.ifEmpty { listOf(LibraryItem.Liked) },
                libraryLoading = false,
                error = msg,
                status = "Error",
                authed = tokenStore.hasSession,
            )
        }
    }

    fun clearConsole() {
        _state.update { it.copy(consoleLines = emptyList()) }
        console(ConsoleLevel.Info, "App", "Console cleared")
    }

    fun console(level: ConsoleLevel, tag: String, message: String) {
        val line = ConsoleLine(
            id = consoleSeq.incrementAndGet(),
            atMs = System.currentTimeMillis(),
            level = level,
            tag = tag.ifBlank { "App" },
            message = message.trim().ifBlank { "(empty)" },
        )
        _state.update { st ->
            val next = st.consoleLines + line
            st.copy(consoleLines = if (next.size > MAX_CONSOLE_LINES) next.takeLast(MAX_CONSOLE_LINES) else next)
        }
    }

    private fun consoleAuto(tag: String, message: String, isError: Boolean = false) {
        console(classifyConsole(message, isError), tag, message)
    }

    private fun classifyConsole(message: String, isError: Boolean): ConsoleLevel {
        val m = message.lowercase()
        return when {
            m.contains("429") ||
                m.contains("retry-after") ||
                m.contains("rate limit") ||
                m.contains("ratelimit") -> ConsoleLevel.RateLimit
            m.contains("captcha") || m.contains("datadome") -> ConsoleLevel.Warn
            m.contains("401") || m.contains("403") || m.contains("session expired") ||
                m.contains("forbidden") -> ConsoleLevel.Warn
            isError || m.contains("failed") || m.contains("error") || m.contains("can't") ||
                m.contains("couldn't") -> ConsoleLevel.Error
            m.contains("loaded") || m.contains("connected") || m.contains("playing") ||
                m.contains("liked") || m.contains("ready") -> ConsoleLevel.Ok
            else -> ConsoleLevel.Info
        }
    }

    private companion object {
        const val MAX_CONSOLE_LINES = 300
    }

    // Cold start: disk only. Network arms on Spotify tab / Liked.
    private fun softStartSpotify() {
        _state.update {
            it.copy(
                authed = true,
                userName = tokenStore.userName.orEmpty(),
                library = it.library.ifEmpty { listOf(LibraryItem.Liked) },
                libraryLoading = false,
                status = when {
                    it.library.size > 1 -> "Ready · cached library"
                    homeSpotifyLikes.isNotEmpty() -> "Ready · cached likes"
                    else -> "Ready · open Spotify tab to sync"
                },
                error = null,
            )
        }
        console(
            ConsoleLevel.Ok,
            "Spotify",
            "Soft start · no API until Spotify tab / Liked / search",
        )
    }

    fun onSpotifyTabOpened() {
        if (!tokenStore.hasSession) return
        // Tab open = disk only; do not arm Pathfinder here.
        val lib = _state.value.library
        if (lib.size <= 1) {
            console(
                ConsoleLevel.Info,
                "Spotify",
                "Cached library empty · use Settings → Refresh library when ready",
            )
            _state.update {
                it.copy(status = "Library from cache · tap Refresh library to sync")
            }
        } else {
            console(ConsoleLevel.Info, "Cache", "Spotify tab · using disk library (${lib.size - 1} items)")
        }
    }

    private fun ensureSpotifyLibraryLoaded() {
        if (!tokenStore.hasSession) return
        val hasCachedLib = _state.value.library.size > 1
        if (hasCachedLib) {
            console(ConsoleLevel.Info, "Cache", "Library already cached · skip network")
            return
        }
        // Never auto-fetch — WebView SPA burns quota. User must tap Refresh library.
        console(
            ConsoleLevel.Warn,
            "Spotify",
            "No library cache · open Settings → Refresh library (manual)",
        )
    }

    private fun refreshSession(forceNetwork: Boolean = false) {
        if (!forceNetwork) {
            softStartSpotify()
            return
        }
        console(
            ConsoleLevel.Warn,
            "Spotify",
            "Loading library via WebView — SPA may burn quota; use cache when possible",
        )
        spotifyNetworkArmed = true
        viewModelScope.launch {
            console(ConsoleLevel.Info, "Spotify", "Loading library…")
            _state.update {
                it.copy(
                    library = it.library.ifEmpty { listOf(LibraryItem.Liked) },
                    libraryLoading = true,
                    status = "Loading library…",
                    authed = true,
                    error = null,
                )
            }
            try {
                _state.update { it.copy(status = "Connecting to Spotify…") }
                console(ConsoleLevel.Info, "Spotify", "Connecting…")
                val (userId, name) = api.currentUser()
                tokenStore.userName = name
                _state.update {
                    it.copy(userName = name, status = "Loading playlists…", error = null)
                }
                console(ConsoleLevel.Ok, "Spotify", "Signed in as $name")
                val (library, warning) = api.loadLibrary()
                libraryCache.save(library)
                val playlistCount = library.count { it is LibraryItem.Playlist }
                val albumCount = library.count { it is LibraryItem.Album }
                _state.update {
                    it.copy(
                        library = library,
                        libraryLoading = false,
                        error = warning,
                        status = "Library · $playlistCount playlists · $albumCount albums",
                    )
                }
                console(
                    ConsoleLevel.Ok,
                    "Spotify",
                    "Library · $playlistCount playlists · $albumCount albums",
                )
                if (!warning.isNullOrBlank()) consoleAuto("Spotify", warning, isError = true)
                // No /me/tracks — that endpoint 429s hard.
            } catch (e: Throwable) {
                // withTimeout throws TimeoutCancellationException (a CancellationException)
                if (e is kotlinx.coroutines.CancellationException &&
                    e !is kotlinx.coroutines.TimeoutCancellationException
                ) {
                    throw e
                }
                val msg = e.message.orEmpty().ifBlank { "Library load failed" }
                if (isAuthFailure(msg)) {
                    tokenStore.clear()
                    console(ConsoleLevel.Error, "Spotify", "Auth failed · $msg")
                    _state.update {
                        it.copy(error = msg, authed = false, libraryLoading = false)
                    }
                } else {
                    softFail(msg)
                }
            }
        }
    }

    fun retryLibrary() {
        if (!tokenStore.hasSession) return
        refreshSession(forceNetwork = true)
    }

    private fun setHomeLikedPreview(tracks: List<TrackItem>) {
        val preview = tracks.take(15)
        if (preview.isEmpty()) {
            homeSpotifyLikes = emptyList()
            publishHomeMergedLikes()
            return
        }
        val now = System.currentTimeMillis()
        homeSpotifyLikes = preview.mapIndexed { index, t ->
            RecentTrack(
                source = MediaSource.Spotify,
                id = t.id,
                uri = t.uri,
                name = t.name,
                artists = t.artists,
                album = t.album,
                imageUrl = t.imageUrl,
                addedAtMs = t.likedAtMs ?: (now - index * 1_000L),
                primaryArtistId = t.primaryArtistId,
            )
        }
        publishHomeMergedLikes()
    }

    // SC likes: cache → probe first song only → keep cache if match, else refresh page.
    private suspend fun syncSoundCloudLikes(updateTab: Boolean) {
        val cached = likedCache.loadSoundCloud()
        if (cached != null && cached.tracks.isNotEmpty()) {
            scLikedIds.clear()
            scLikedIds.addAll(cached.tracks.map { it.id })
            applyHomeScLikes(cached.tracks)
            if (updateTab) {
                _state.update {
                    it.copy(
                        scTracks = cached.tracks,
                        scLoading = false,
                        scStatus = "Liked · cached ${cached.tracks.size}",
                        scSection = ScBrowseSection.Liked,
                        scError = null,
                    )
                }
            }
            val probe = runCatching { scApi.getLikedTracks(1) }.getOrNull()
            if (probe != null &&
                LikedLibraryCache.headMatches(cached.tracks, probe.items) { it.id.toString() }
            ) {
                console(ConsoleLevel.Ok, "Cache", "SoundCloud liked unchanged · skip (first song match)")
                return
            }
            if (probe == null) {
                console(
                    ConsoleLevel.Warn,
                    "Cache",
                    "SoundCloud head probe failed · keeping disk (${cached.tracks.size})",
                )
                return
            }
            console(ConsoleLevel.Info, "Cache", "SoundCloud liked changed · refreshing…")
        }

        runCatching { scApi.getLikedTracks(50) }
            .onSuccess { page ->
                val remote = page.items
                likedCache.saveSoundCloud(remote)
                scLikedIds.clear()
                scLikedIds.addAll(remote.map { it.id })
                applyHomeScLikes(remote)
                console(
                    ConsoleLevel.Ok,
                    "Cache",
                    if (cached == null) "SoundCloud liked · cached ${remote.size}"
                    else "SoundCloud liked updated · ${remote.size}",
                )
                if (updateTab) {
                    _state.update {
                        it.copy(
                            scTracks = remote,
                            scLoading = false,
                            scStatus = "Liked · ${remote.size}",
                            scSection = ScBrowseSection.Liked,
                            scError = null,
                        )
                    }
                }
                val np = _state.value.nowPlaying
                if (np?.source == MediaSource.SoundCloud) {
                    val tid = np.id?.toLongOrNull()
                    if (tid != null) {
                        _state.update { s -> s.copy(trackLiked = tid in scLikedIds) }
                    }
                }
            }
            .onFailure { e ->
                consoleAuto("SoundCloud", e.message ?: "Liked sync failed", isError = true)
                if (updateTab && _state.value.scTracks.isEmpty()) {
                    _state.update {
                        it.copy(scLoading = false, scError = e.message, scStatus = "Error")
                    }
                } else if (updateTab) {
                    _state.update { it.copy(scLoading = false) }
                }
            }
    }

    private fun refreshHomeScLikes(forceNetwork: Boolean = false) {
        if (!scStore.hasSession) return
        viewModelScope.launch {
            if (!forceNetwork) {
                likedCache.loadSoundCloud()?.let { snap ->
                    applyHomeScLikes(snap.tracks)
                    scLikedIds.addAll(snap.tracks.map { it.id })
                }
            }
            console(ConsoleLevel.Info, "SoundCloud", "Home likes · head check")
            syncSoundCloudLikes(updateTab = false)
        }
    }

    private fun applyHomeScLikes(tracks: List<ScTrack>) {
        val preview = tracks.take(15)
        homeScTracksCache = preview
        val now = System.currentTimeMillis()
        homeSoundCloudLikes = preview.mapIndexed { index, t ->
            RecentTrack(
                source = MediaSource.SoundCloud,
                id = t.id.toString(),
                uri = t.uri,
                name = t.title,
                artists = t.artist,
                album = "",
                imageUrl = t.artworkUrl,
                addedAtMs = t.likedAtMs() ?: (now - index * 1_000L),
                scUserId = t.userId,
                permalinkUrl = t.permalinkUrl.takeIf { it.isNotBlank() },
            )
        }
        publishHomeMergedLikes()
    }

    private fun publishHomeMergedLikes() {
        val merged = (homeSpotifyLikes + homeSoundCloudLikes)
            .sortedByDescending { it.addedAtMs }
        _state.update { it.copy(recents = merged) }
    }

    fun homeLikedQueue(): List<TrackItem> =
        _state.value.recents
            .filter { it.source == MediaSource.Spotify }
            .map {
                TrackItem(
                    id = it.id,
                    uri = it.uri,
                    name = it.name,
                    artists = it.artists,
                    album = it.album,
                    imageUrl = it.imageUrl,
                    durationMs = 0L,
                )
            }

    fun homeScLikedQueue(): List<ScTrack> {
        val order = _state.value.recents
            .filter { it.source == MediaSource.SoundCloud }
            .map { it.id }
        val byId = homeScTracksCache.associateBy { it.id.toString() }
        return order.mapNotNull { byId[it] }.ifEmpty { homeScTracksCache }
    }

    fun homeScTrack(id: String): ScTrack? =
        homeScTracksCache.firstOrNull { it.id.toString() == id }

    // Spotify likes: disk first; network only when asked.
    private fun loadLikedHomePreview(allowNetwork: Boolean = false) {
        viewModelScope.launch {
            val cached = likedCache.loadSpotify()
            if (cached != null) {
                setHomeLikedPreview(cached.tracks)
                if (_state.value.selectedId == "liked" && _state.value.tracks.isEmpty()) {
                    _state.update {
                        it.copy(
                            tracks = cached.tracks,
                            tracksLoading = false,
                            status = "Liked · cached ${cached.tracks.size}",
                        )
                    }
                }
            }
            if (!allowNetwork) {
                console(ConsoleLevel.Info, "Cache", "Spotify songs · disk only")
                return@launch
            }
            // Already have songs on disk — never auto-probe on home
            if (cached != null) {
                console(ConsoleLevel.Ok, "Cache", "Spotify songs · using disk (${cached.tracks.size})")
                return@launch
            }
            spotifyNetworkArmed = true
            console(ConsoleLevel.Info, "Spotify", "No song cache · fetch 15")
            runCatching { api.likedTracksPreview(15) }
                .onSuccess { tracks ->
                    likedCache.saveSpotify(tracks, total = null)
                    setHomeLikedPreview(tracks)
                    console(ConsoleLevel.Ok, "Cache", "Spotify songs · cached ${tracks.size}")
                }
                .onFailure { e ->
                    consoleAuto("Spotify", e.message ?: "Home likes failed", isError = true)
                }
        }
    }

    // Liked via Pathfinder: disk → probe first song → full page only if mismatch.
    private suspend fun syncSpotifyLikedFull(gen: Int, onPage: (List<TrackItem>) -> Unit): List<TrackItem> {
        val cached = likedCache.loadSpotify()
        if (cached != null && cached.tracks.isNotEmpty()) {
            onPage(cached.tracks)
            setHomeLikedPreview(cached.tracks)

            console(ConsoleLevel.Info, "Spotify", "Liked · probe first song…")
            val head = try {
                api.likedTracksPreview(1)
            } catch (e: Exception) {
                console(
                    ConsoleLevel.Warn,
                    "Cache",
                    "Head probe failed · keeping disk (${cached.tracks.size})",
                )
                return cached.tracks
            }
            if (gen != loadGen) return cached.tracks

            if (LikedLibraryCache.headMatches(cached.tracks, head) { it.id }) {
                console(
                    ConsoleLevel.Ok,
                    "Cache",
                    "Spotify liked unchanged · skip full fetch (${cached.tracks.size})",
                )
                return cached.tracks
            }
            console(ConsoleLevel.Info, "Spotify", "Liked changed · Pathfinder full refresh…")
        } else {
            console(ConsoleLevel.Info, "Spotify", "No song cache · Pathfinder full liked…")
        }

        val tracks = api.likedTracksProgressive { partial ->
            if (gen != loadGen) return@likedTracksProgressive
            onPage(partial)
            if (partial.size <= 50) setHomeLikedPreview(partial)
        }
        if (gen != loadGen) return tracks
        likedCache.saveSpotify(tracks, total = tracks.size)
        setHomeLikedPreview(tracks)
        console(ConsoleLevel.Ok, "Cache", "Songs saved via Pathfinder · ${tracks.size}")
        return tracks
    }

    fun forceRefreshLikedSongs() {
        if (!tokenStore.hasSession) return
        viewModelScope.launch {
            likedCache.clearSpotify()
            homeSpotifyLikes = emptyList()
            console(ConsoleLevel.Warn, "Spotify", "Force refresh Liked Songs (Pathfinder)…")
            loadSelection("liked")
        }
    }

    // Optional Dev OAuth Liked Songs path (separate from web session).
    fun refreshSongsWithDeveloperToken(activity: android.app.Activity) {
        val cid = secrets.spotifyClientId.orEmpty()
        if (cid.isBlank() || !secrets.hasSpotifyPlaybackCreds) {
            console(ConsoleLevel.Error, "Spotify", "Add Spotify Client ID + Secret in Setup / Settings first")
            _state.update { it.copy(error = "Missing Client ID + Secret — add them in Settings") }
            return
        }
        viewModelScope.launch {
            console(ConsoleLevel.Info, "Spotify", "Dev token songs · client ${cid.take(8)}…")
            val token = runCatching { ensureDeveloperAccessToken() }.getOrNull()
            if (token != null) {
                fetchLikedWithDeveloperToken(token)
                return@launch
            }
            console(ConsoleLevel.Info, "Spotify", "Authorize Developer app (browser)…")
            console(
                ConsoleLevel.Info,
                "Spotify",
                "Redirect must be allowlisted: ${DevOAuth.REDIRECT_URI}",
            )
            tokenStore.clearOauth()
            val verifier = Pkce.createVerifier()
            tokenStore.pendingPkceVerifier = verifier
            val challenge = Pkce.challengeS256(verifier)
            DevOAuth.openLogin(activity, cid, challenge)
        }
    }

    fun onDeveloperAuthRedirect(uri: android.net.Uri) {
        if (!DevOAuth.isDevRedirect(uri)) return
        viewModelScope.launch {
            val error = DevOAuth.errorFromRedirect(uri)
            if (!error.isNullOrBlank() && DevOAuth.codeFromRedirect(uri).isNullOrBlank()) {
                console(ConsoleLevel.Error, "Spotify", "Dev auth error · $error · uri=$uri")
                val hint = when {
                    error.contains("server_error", ignoreCase = true) ->
                        "Usually bad Redirect URI in Dashboard — add exactly: ${DevOAuth.REDIRECT_URI}"
                    else -> null
                }
                if (hint != null) console(ConsoleLevel.Warn, "Spotify", hint)
                _state.update { it.copy(error = error) }
                return@launch
            }
            val code = DevOAuth.codeFromRedirect(uri)
            val verifier = tokenStore.pendingPkceVerifier
            if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
                console(ConsoleLevel.Error, "Spotify", "Dev auth missing code/verifier")
                return@launch
            }
            try {
                console(ConsoleLevel.Info, "Spotify", "Exchanging Developer auth code…")
                val tokens = TokenExchanger.exchangeCode(
                    clientId = secrets.spotifyClientId.orEmpty(),
                    code = code,
                    codeVerifier = verifier,
                    clientSecret = secrets.spotifyClientSecret,
                )
                tokenStore.saveOauthTokens(
                    access = tokens.accessToken,
                    refresh = tokens.refreshToken,
                    expiresInSec = tokens.expiresIn,
                )
                tokenStore.pendingPkceVerifier = null
                console(ConsoleLevel.Ok, "Spotify", "Developer token ready")
                fetchLikedWithDeveloperToken(tokens.accessToken)
            } catch (e: Exception) {
                consoleAuto("Spotify", e.message ?: "Dev token exchange failed", isError = true)
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    private suspend fun ensureDeveloperAccessToken(): String {
        val access = tokenStore.oauthAccessToken
        if (!access.isNullOrBlank() && !tokenStore.oauthExpired) return access
        val refresh = tokenStore.oauthRefreshToken
            ?: error("No Developer token — authorize once")
        console(ConsoleLevel.Info, "Spotify", "Refreshing Developer token…")
        val cid = secrets.spotifyClientId.orEmpty()
        if (cid.isBlank()) error("Add Spotify Client ID in Settings")
        val tokens = TokenExchanger.refresh(cid, refresh, secrets.spotifyClientSecret)
        tokenStore.saveOauthTokens(
            access = tokens.accessToken,
            refresh = tokens.refreshToken ?: refresh,
            expiresInSec = tokens.expiresIn,
        )
        return tokens.accessToken
    }

    private suspend fun fetchLikedWithDeveloperToken(accessToken: String) {
        _state.update {
            it.copy(
                tracksLoading = true,
                tracksRefreshing = true,
                status = "Loading Liked (Developer token)…",
                error = null,
                selectedId = "liked",
            )
        }
        try {
            val tracks = api.likedTracksWithBearer(accessToken) { partial ->
                _state.update {
                    it.copy(
                        tracks = partial,
                        tracksLoading = false,
                        tracksRefreshing = true,
                        status = "Dev token · ${partial.size}…",
                    )
                }
                setHomeLikedPreview(partial)
            }
            likedCache.saveSpotify(tracks, total = tracks.size)
            setHomeLikedPreview(tracks)
            _state.update {
                it.copy(
                    tracks = tracks,
                    tracksLoading = false,
                    tracksRefreshing = false,
                    status = "Liked · ${tracks.size} (Developer token)",
                    error = null,
                )
            }
            console(ConsoleLevel.Ok, "Spotify", "Liked Songs · ${tracks.size} via Developer token")
        } catch (e: Exception) {
            consoleAuto("Spotify", e.message ?: "Dev liked fetch failed", isError = true)
            _state.update {
                it.copy(
                    tracksLoading = false,
                    tracksRefreshing = false,
                    error = e.message,
                    status = "Dev token liked failed",
                )
            }
        }
    }

    fun loadSelection(
        id: String,
        titleOverride: String? = null,
        subtitleOverride: String? = null,
        artistNameForFetch: String? = null,
        artistIdForFetch: String? = null,
    ) {
        viewModelScope.launch {
            val gen = ++loadGen
            spotifyNetworkArmed = true
            val label = when {
                id == "liked" -> "Liked Songs"
                id.startsWith("album:") -> "Album"
                id.startsWith("artist:") -> titleOverride ?: "Artist"
                else -> "Playlist"
            }
            console(ConsoleLevel.Info, "Spotify", "Loading $label…")
            _state.update {
                it.copy(
                    selectedId = id,
                    detailTitle = titleOverride.orEmpty(),
                    detailSubtitle = subtitleOverride.orEmpty(),
                    searchQuery = "",
                    searchResults = emptyList(),
                    searchArtists = emptyList(),
                    searchAlbums = emptyList(),
                    error = null,
                    tracks = emptyList(),
                    tracksLoading = true,
                    tracksRefreshing = false,
                    status = "Loading songs…",
                )
            }
            val maxAttempts = 3
            var lastError: Exception? = null
            repeat(maxAttempts) { attempt ->
                if (gen != loadGen) return@launch
                if (attempt > 0) {
                    console(ConsoleLevel.Warn, "Spotify", "Retrying $label (${attempt + 1}/$maxAttempts)…")
                }
                _state.update {
                    it.copy(
                        tracksLoading = true,
                        error = null,
                        status = if (attempt == 0) {
                            "Loading songs…"
                        } else {
                            "Retrying load (${attempt + 1}/$maxAttempts)…"
                        },
                    )
                }
                try {
                    val onPage: (List<TrackItem>) -> Unit = { partial ->
                        if (gen == loadGen) {
                            // First page arrived (~50) — hide spinner; Home takes 1–15 immediately
                            _state.update {
                                it.copy(
                                    tracks = partial,
                                    tracksLoading = false,
                                    tracksRefreshing = true,
                                    status = "Loaded ${partial.size}…",
                                )
                            }
                            if (id == "liked") setHomeLikedPreview(partial)
                            if (partial.size <= 50) {
                                console(ConsoleLevel.Info, "Spotify", "$label · first page ${partial.size}")
                            }
                        }
                    }
                    val tracks = when {
                        id == "liked" -> syncSpotifyLikedFull(gen, onPage)
                        id.startsWith("album:") ->
                            api.albumTracksProgressive(id.removePrefix("album:"), onPage)
                        id.startsWith("artist:") -> {
                            val name = artistNameForFetch
                                ?: titleOverride
                                ?: id.removePrefix("artist:").removePrefix("name:")
                            val aid = artistIdForFetch
                                ?: id.removePrefix("artist:").takeUnless { it.startsWith("name:") }
                            val list = api.artistTopTracks(aid, name, limit = 10)
                            onPage(list)
                            list
                        }
                        else -> api.playlistTracksProgressive(id, onPage)
                    }
                    if (gen != loadGen) return@launch
                    _state.update {
                        it.copy(
                            tracks = tracks,
                            tracksLoading = false,
                            tracksRefreshing = false,
                            error = null,
                            status = "Loaded ${tracks.size} tracks",
                        )
                    }
                    console(ConsoleLevel.Ok, "Spotify", "$label · ${tracks.size} tracks")
                    if (id == "liked") setHomeLikedPreview(tracks)
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    val friendly = friendlyLoadError(e)
                    consoleAuto("Spotify", friendly, isError = true)
                    val rateLimited = friendly.contains("429") ||
                        friendly.contains("Retry-After", ignoreCase = true) ||
                        api.isRateLimited()
                    if (rateLimited) {
                        val wait = api.rateLimitRemainingMs().coerceAtLeast(3_000L)
                        console(
                            ConsoleLevel.RateLimit,
                            "Spotify",
                            "Stopping retries · cooling ${wait / 1000}s",
                        )
                        delay(wait)
                        lastError = Exception(friendly)
                        return@repeat // don't burn more quota
                    }
                    if (attempt < maxAttempts - 1) {
                        _state.update {
                            it.copy(
                                status = "Load failed — retrying (${attempt + 2}/$maxAttempts)…",
                                error = friendly,
                            )
                        }
                        delay(700L * (attempt + 1))
                    } else {
                        lastError = Exception(friendly)
                    }
                }
            }
            if (gen != loadGen) return@launch
            console(ConsoleLevel.Error, "Spotify", "Load failed · ${lastError?.message ?: label}")
            _state.update {
                it.copy(
                    tracksLoading = false,
                    tracksRefreshing = false,
                    error = lastError?.message ?: "Couldn't load tracks",
                    status = "Load failed",
                )
            }
        }
    }

    fun retryTracks() {
        val s = _state.value
        val id = s.selectedId
        if (id.isBlank()) return
        if (id.startsWith("artist:")) {
            loadSelection(
                id = id,
                titleOverride = s.detailTitle.ifBlank { null },
                subtitleOverride = s.detailSubtitle.ifBlank { null },
                artistNameForFetch = s.detailTitle.ifBlank { null },
                artistIdForFetch = id.removePrefix("artist:").takeUnless { it.startsWith("name:") },
            )
        } else {
            loadSelection(id)
        }
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _state.update {
                it.copy(
                    searchResults = emptyList(),
                    searchArtists = emptyList(),
                    searchAlbums = emptyList(),
                    searching = false,
                )
            }
            return
        }
        spotifyNetworkArmed = true
        viewModelScope.launch {
            _state.update { it.copy(searching = true, status = "Searching…") }
            try {
                val results = api.search(query)
                _state.update {
                    it.copy(
                        searchResults = results.songs,
                        searchArtists = results.artists,
                        searchAlbums = results.albums,
                        searching = false,
                        status = "Found ${results.totalCount}",
                        error = null,
                    )
                }
                console(
                    ConsoleLevel.Ok,
                    "Spotify",
                    "Search · ${results.songs.size} songs · ${results.artists.size} people · ${results.albums.size} albums",
                )
            } catch (e: Exception) {
                consoleAuto("Spotify", e.message ?: "Search failed", isError = true)
                _state.update {
                    it.copy(
                        searchResults = emptyList(),
                        searchArtists = emptyList(),
                        searchAlbums = emptyList(),
                        searching = false,
                        error = e.message,
                        status = "Search failed",
                    )
                }
            }
        }
    }

    fun openSpotifyAlbum(albumId: String, albumName: String) {
        if (albumId.isBlank()) return
        loadSelection(
            id = "album:$albumId",
            titleOverride = albumName.ifBlank { "Album" },
            subtitleOverride = "Album",
        )
    }

    fun playTrack(track: TrackItem, list: List<TrackItem>) {
        scPlayer.stop()
        optimisticNow(track, isPlaying = false)
        viewModelScope.launch {
            try {
                _state.update { it.copy(status = "Starting “${track.name}”…") }
                prepareQueue(track, list, shuffle = _state.value.shuffle)
                runCatching { appRemote.setShuffle(false) }
                startQueueTrack(track, queueIndex)
                clearPending()
                _state.update {
                    it.copy(
                        status = "Playing · ${queueIndex + 1}/${playQueue.size}",
                        error = null,
                    )
                }
            } catch (e: Exception) {
                clearPending()
                _state.update {
                    it.copy(
                        error = e.message ?: "Playback failed",
                        status = "Can't play",
                        nowPlaying = it.nowPlaying?.copy(isPlaying = false),
                    )
                }
            }
        }
    }

    fun playSelected() {
        val list = visibleTracks()
        val first = list.firstOrNull() ?: return
        // Play-all = start at first (or shuffled first) using same queue logic as tapping a row
        val start = if (_state.value.shuffle) list.random() else first
        playTrack(start, list)
    }

    // Queue = album order unless shuffle (avoids Next jumping to track 1).
    private fun prepareQueue(start: TrackItem, list: List<TrackItem>, shuffle: Boolean) {
        playQueue = if (shuffle) {
            buildShuffledQueue(start, list)
        } else {
            list
        }
        queueIndex = indexInQueue(playQueue, start.uri, start.id).coerceAtLeast(0)
        // Album/playlist: skipToIndex (playUri on a track often restarts the album at #1)
        spotifyContextUri = if (shuffle) null else contextUriForSelection()
    }

    private fun contextUriForSelection(): String? {
        val id = _state.value.selectedId
        val selected = _state.value.library.find { it.id == id }
        return when (selected) {
            is LibraryItem.Album -> selected.uri.takeIf { it.isNotBlank() }
            is LibraryItem.Playlist -> selected.uri.takeIf { it.isNotBlank() }
            else -> null // Liked / search — no skipToIndex context
        }
    }

    private fun buildShuffledQueue(current: TrackItem, list: List<TrackItem>): List<TrackItem> {
        val rest = list.filter { it.uri != current.uri && it.id != current.id }
        return listOf(current) + rest.shuffled()
    }

    // Prefer skipToIndex so Next uses album index, not a filtered queue.
    private suspend fun startQueueTrack(track: TrackItem, queueIdx: Int) {
        val context = spotifyContextUri
        if (context != null) {
            val absolute = indexInQueue(_state.value.tracks, track.uri, track.id)
                .takeIf { it >= 0 } ?: queueIdx
            runCatching {
                appRemote.skipToIndex(context, absolute)
            }.getOrElse {
                // Fall back if context skip fails (restricted playlist, etc.)
                appRemote.playUri(track.uri)
            }
        } else {
            appRemote.playUri(track.uri)
        }
    }

    fun togglePlay() {
        viewModelScope.launch {
            try {
                val np = _state.value.nowPlaying
                if (np?.source == MediaSource.SoundCloud) {
                    if (np.isPlaying) {
                        scPlayer.pause()
                        _state.update { it.copy(status = "Paused") }
                    } else {
                        scPlayer.resume()
                        _state.update { it.copy(status = "Playing") }
                    }
                    return@launch
                }
                val playing = np?.isPlaying == true
                when {
                    playing -> {
                        appRemote.pause()
                        _state.update {
                            it.copy(nowPlaying = it.nowPlaying?.copy(isPlaying = false), status = "Paused")
                        }
                        playbackCache.save(_state.value.nowPlaying)
                    }
                    np != null && !np.uri.isNullOrBlank() -> {
                        scPlayer.stop()
                        _state.update { it.copy(status = "Resuming “${np.name}”…") }
                        appRemote.resumeOrPlayAt(np.uri, np.progressMs)
                        clearPending()
                        _state.update { it.copy(status = "Playing", error = null) }
                    }
                    else -> {
                        scPlayer.stop()
                        appRemote.resume()
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, status = "Can't resume") }
            }
        }
    }

    fun next() = viewModelScope.launch {
        if (_state.value.nowPlaying?.source == MediaSource.SoundCloud) {
            advanceScQueue(+1)
        } else {
            advanceInQueue(+1)
        }
    }

    fun previous() = viewModelScope.launch {
        if (_state.value.nowPlaying?.source == MediaSource.SoundCloud) {
            advanceScQueue(-1)
        } else {
            advanceInQueue(-1)
        }
    }

    private suspend fun advanceInQueue(delta: Int) {
        clearPending()
        if (playQueue.isEmpty()) {
            val fallback = visibleTracks()
            if (fallback.isEmpty()) {
                _state.update { it.copy(status = "Nothing in queue") }
                return
            }
            prepareQueue(
                start = fallback.firstOrNull {
                    it.uri == _state.value.nowPlaying?.uri || it.id == _state.value.nowPlaying?.id
                } ?: fallback.first(),
                list = fallback,
                shuffle = _state.value.shuffle,
            )
        }
        val list = playQueue
        // Prefer our remembered index; only re-resolve if Spotify drifted
        val np = _state.value.nowPlaying
        if (queueIndex !in list.indices || !sameTrack(list[queueIndex], np?.uri, np?.id)) {
            val found = indexInQueue(list, np?.uri, np?.id)
            if (found >= 0) {
                queueIndex = found
            } else if (queueIndex !in list.indices) {
                _state.update { it.copy(status = "Queue out of sync — tap a song in the list") }
                return
            }
            // else keep queueIndex even if Spotify URI looks different (pending/glitch)
        }
        val newIdx = queueIndex + delta
        if (newIdx !in list.indices) {
            _state.update {
                it.copy(status = if (delta > 0) "End of queue" else "Start of queue")
            }
            return
        }
        val track = list[newIdx]
        queueIndex = newIdx
        optimisticNow(track, isPlaying = false)
        _state.update {
            it.copy(status = "${if (delta > 0) "Next" else "Previous"}: ${track.name} (${newIdx + 1}/${list.size})")
        }
        runCatching {
            startQueueTrack(track, newIdx)
            clearPending()
            _state.update {
                it.copy(status = "Playing · ${newIdx + 1}/${list.size}", error = null)
            }
        }.onFailure { e ->
            clearPending()
            _state.update {
                it.copy(
                    error = e.message,
                    status = "Can't skip",
                    nowPlaying = it.nowPlaying?.copy(isPlaying = false),
                )
            }
        }
    }

    private fun sameTrack(track: TrackItem, uri: String?, id: String?): Boolean {
        if (!uri.isNullOrBlank() && (track.uri == uri || track.uri.endsWith(uri.substringAfterLast(':')))) {
            return true
        }
        if (!id.isNullOrBlank() && (track.id == id || track.uri.endsWith(id))) return true
        val fromUri = trackIdFromUri(uri)
        return !fromUri.isNullOrBlank() && (track.id == fromUri || track.uri.endsWith(fromUri))
    }

    private fun indexInQueue(
        list: List<TrackItem>,
        uri: String?,
        id: String?,
    ): Int {
        if (list.isEmpty()) return -1
        if (!uri.isNullOrBlank()) {
            val exact = list.indexOfFirst { it.uri == uri }
            if (exact >= 0) return exact
            val trackId = trackIdFromUri(uri)
            if (!trackId.isNullOrBlank()) {
                val byId = list.indexOfFirst { it.id == trackId || it.uri.endsWith(trackId) }
                if (byId >= 0) return byId
            }
        }
        if (!id.isNullOrBlank()) {
            val byId = list.indexOfFirst { it.id == id }
            if (byId >= 0) return byId
        }
        return -1
    }

    fun onVolumeDrag(value: Float) {
        val v = value.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        systemVolume.setNormalized(v)
    }

    fun commitVolume() {
        systemVolume.setNormalized(_state.value.volume)
    }

    fun toggleShuffle() {
        viewModelScope.launch {
            val enable = !_state.value.shuffle
            _state.update { it.copy(shuffle = enable, error = null) }

            // Local queue only — Spotify setShuffle is unreliable / not allowed in some contexts
            runCatching { appRemote.setShuffle(false) }
            val list = visibleTracks().ifEmpty { playQueue }
            if (list.isEmpty()) return@launch
            val currentUri = _state.value.nowPlaying?.uri ?: return@launch
            val currentTrack = list.firstOrNull {
                it.uri == currentUri || it.id == trackIdFromUri(currentUri)
            } ?: TrackItem(
                id = trackIdFromUri(currentUri).orEmpty(),
                uri = currentUri,
                name = _state.value.nowPlaying?.name.orEmpty(),
                artists = _state.value.nowPlaying?.artists.orEmpty(),
                album = _state.value.nowPlaying?.album.orEmpty(),
                imageUrl = _state.value.nowPlaying?.imageUrl,
                durationMs = _state.value.nowPlaying?.durationMs ?: 0L,
            )
            prepareQueue(currentTrack, list, shuffle = enable)
            _state.update {
                it.copy(
                    status = if (enable) {
                        "Shuffle on · ${playQueue.size} in queue"
                    } else {
                        "Shuffle off · track ${queueIndex + 1}/${playQueue.size}"
                    },
                )
            }
        }
    }

    fun setScLikeMode(mode: ScLikeMode) {
        scStore.likeMode = mode
        _state.update {
            it.copy(
                scLikeMode = mode,
                status = when (mode) {
                    ScLikeMode.WebView -> "SoundCloud likes · open track (safer)"
                    ScLikeMode.Api -> "SoundCloud likes · API"
                },
                // Close any open like UI when switching modes
                scTrackLikeUrl = null,
                scCaptchaUrl = null,
            )
        }
        pendingScLike = null
    }

    fun dismissScTrackLike() {
        _state.update {
            it.copy(scTrackLikeUrl = null, scTrackLikeName = "", status = "Like cancelled")
        }
    }

    fun onScTrackLikeDone() {
        val url = _state.value.scTrackLikeUrl
        val name = _state.value.scTrackLikeName
        _state.update {
            it.copy(
                scTrackLikeUrl = null,
                scTrackLikeName = "",
                status = if (name.isNotBlank()) "Check liked · $name" else "Done",
            )
        }
        // Light re-sync after user liked in WebView (head check, not full ID crawl)
        refreshHomeScLikes(forceNetwork = true)
        // Also re-check current track
        val np = _state.value.nowPlaying
        if (np?.source == MediaSource.SoundCloud && !url.isNullOrBlank()) {
            lastLikedUri = null
            refreshLikeAndLyrics(np)
        }
    }

    fun toggleLike() {
        val np = _state.value.nowPlaying ?: return
        if (np.source == MediaSource.SoundCloud) {
            val id = np.id?.toLongOrNull() ?: return
            val permalink = scQueue.getOrNull(scQueueIndex)?.takeIf { it.id == id }?.permalinkUrl
                ?: scQueue.firstOrNull { it.id == id }?.permalinkUrl

            // WebView: user likes on the track page
            if (_state.value.scLikeMode == ScLikeMode.WebView) {
                viewModelScope.launch {
                    val url = permalink?.takeIf { it.isNotBlank() }
                        ?: runCatching { scApi.fetchTrack(id).permalinkUrl }.getOrNull()
                    if (url.isNullOrBlank()) {
                        _state.update {
                            it.copy(
                                error = "No SoundCloud link for this track",
                                status = "Can't open track to like",
                            )
                        }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            scTrackLikeUrl = url,
                            scTrackLikeName = np.name,
                            status = "Open track · tap ♥ then Done",
                            error = null,
                        )
                    }
                }
                return
            }

            // API like (may CAPTCHA)
            val currentlyLiked = id in scLikedIds || _state.value.trackLiked
            val wantLiked = !currentlyLiked
            if (currentlyLiked) scLikedIds.remove(id) else scLikedIds.add(id)
            _state.update {
                it.copy(
                    trackLiked = wantLiked,
                    status = if (wantLiked) "Liking…" else "Unliking…",
                    error = null,
                )
            }
            viewModelScope.launch {
                runCatching {
                    if (wantLiked) scApi.likeTrack(id, permalink)
                    else scApi.unlikeTrack(id, permalink)
                }.onSuccess {
                    pendingScLike = null
                    _state.update {
                        it.copy(
                            status = if (wantLiked) "Liked · ${np.name}" else "Unliked · ${np.name}",
                            error = null,
                            scCaptchaUrl = null,
                        )
                    }
                }.onFailure { e ->
                    if (wantLiked) scLikedIds.remove(id) else scLikedIds.add(id)
                    val captcha = (e as? SoundCloudCaptchaNeeded)?.captchaUrl
                        ?: SoundCloudApiClient.extractCaptchaUrl(e.message)
                    if (captcha != null && wantLiked) {
                        pendingScLike = PendingScLike(
                            trackId = id,
                            permalink = permalink,
                            wantLiked = true,
                            trackName = np.name,
                        )
                        _state.update {
                            it.copy(
                                trackLiked = false,
                                scCaptchaUrl = captcha,
                                status = "Solve CAPTCHA to like",
                                error = null,
                            )
                        }
                        console(ConsoleLevel.Warn, "SoundCloud", "CAPTCHA required to like")
                        return@onFailure
                    }
                    val detail = buildString {
                        append(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                        e.cause?.message?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    }.take(220)
                    consoleAuto("SoundCloud", detail, isError = true)
                    _state.update {
                        it.copy(
                            trackLiked = currentlyLiked,
                            error = detail,
                            status = detail,
                        )
                    }
                }
            }
            return
        }
        val id = np.id ?: trackIdFromUri(np.uri) ?: return
        val currentlyLiked = _state.value.trackLiked
        _state.update { it.copy(trackLiked = !currentlyLiked) }
        viewModelScope.launch {
            runCatching {
                if (currentlyLiked) api.removeTrack(id) else api.saveTrack(id)
            }.onFailure { e ->
                _state.update {
                    it.copy(trackLiked = currentlyLiked, error = e.message ?: "Like failed")
                }
            }
        }
    }

    fun dismissScCaptcha() {
        pendingScLike = null
        _state.update {
            it.copy(scCaptchaUrl = null, status = "Like cancelled", error = null)
        }
    }

    fun onScCaptchaSolved() {
        val pending = pendingScLike
        _state.update { it.copy(scCaptchaUrl = null, status = "Retrying like…", error = null) }
        if (pending == null) return
        viewModelScope.launch {
            // Give CookieManager a moment to flush captcha cookies
            delay(400)
            runCatching {
                if (pending.wantLiked) {
                    scApi.likeTrack(pending.trackId, pending.permalink)
                } else {
                    scApi.unlikeTrack(pending.trackId, pending.permalink)
                }
            }.onSuccess {
                if (pending.wantLiked) scLikedIds.add(pending.trackId) else scLikedIds.remove(pending.trackId)
                pendingScLike = null
                _state.update {
                    it.copy(
                        trackLiked = pending.wantLiked,
                        status = if (pending.wantLiked) {
                            "Liked · ${pending.trackName}"
                        } else {
                            "Unliked · ${pending.trackName}"
                        },
                        error = null,
                        scCaptchaUrl = null,
                    )
                }
            }.onFailure { e ->
                val captcha = (e as? SoundCloudCaptchaNeeded)?.captchaUrl
                    ?: SoundCloudApiClient.extractCaptchaUrl(e.message)
                if (captcha != null && pending.wantLiked) {
                    _state.update {
                        it.copy(
                            scCaptchaUrl = captcha,
                            status = "CAPTCHA again — solve and tap Done",
                            error = null,
                        )
                    }
                    return@onFailure
                }
                pendingScLike = null
                _state.update {
                    it.copy(
                        trackLiked = false,
                        error = e.message ?: "Like failed after CAPTCHA",
                        status = e.message ?: "Like failed after CAPTCHA",
                    )
                }
            }
        }
    }

    fun visibleTracks(): List<TrackItem> {
        val s = _state.value
        return if (s.searchQuery.isNotBlank()) s.searchResults else s.tracks
    }

    private fun clearPending() {
        pendingPlaybackUri = null
        pendingUntilMs = 0L
    }

    private fun applyPlayerState(np: NowPlaying) {
        // Don't let Spotify App Remote overwrite in-app SoundCloud now-playing
        if (scPlayer.isActiveSource || _state.value.nowPlaying?.source == MediaSource.SoundCloud) {
            if (np.isPlaying) {
                scPlayer.stop()
            } else {
                return
            }
        }
        val pending = pendingPlaybackUri
        val waiting = pending != null && System.currentTimeMillis() < pendingUntilMs
        if (waiting && !np.uri.isNullOrBlank() && np.uri != pending) return
        if (pending != null && (np.uri == pending || !waiting)) clearPending()

        val current = _state.value.nowPlaying
        val uriChanged = current?.uri != np.uri
        // Keep queue index in sync when Spotify advances on its own (end of track)
        if (uriChanged && playQueue.isNotEmpty() && !np.uri.isNullOrBlank()) {
            val found = indexInQueue(playQueue, np.uri, np.id)
            if (found >= 0) queueIndex = found
        }
        // Never let Spotify's shuffle flag overwrite our toggle
        _state.update { s ->
            s.copy(
                nowPlaying = np.copy(
                    imageUrl = np.imageUrl ?: current?.imageUrl,
                    shuffle = s.shuffle,
                    source = MediaSource.Spotify,
                ),
            )
        }
        persistPlayback(_state.value.nowPlaying)
        if (uriChanged) refreshLikeAndLyrics(np)
        if (np.isPlaying) startProgressTick() else stopProgressTick()
    }

    private fun persistPlayback(np: NowPlaying?, force: Boolean = false) {
        if (np == null || np.uri.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCacheSaveMs < 2_000L) return
        lastCacheSaveMs = now
        playbackCache.save(np)
    }

    private fun startProgressTick() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val np = _state.value.nowPlaying ?: continue
                if (!np.isPlaying || np.durationMs <= 0) continue
                val next = (np.progressMs + 500).coerceAtMost(np.durationMs)
                if (next != np.progressMs) {
                    _state.update { it.copy(nowPlaying = np.copy(progressMs = next)) }
                    persistPlayback(_state.value.nowPlaying)
                }
            }
        }
    }

    private fun stopProgressTick() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun TrackItem.toNowPlaying(isPlaying: Boolean = true) = NowPlaying(
        id = id,
        name = name,
        artists = artists,
        album = album,
        imageUrl = imageUrl,
        uri = uri,
        isPlaying = isPlaying,
        progressMs = 0,
        durationMs = durationMs,
        shuffle = _state.value.shuffle,
        source = MediaSource.Spotify,
    )

    private fun optimisticNow(track: TrackItem, isPlaying: Boolean = true) {
        pendingPlaybackUri = track.uri
        pendingUntilMs = System.currentTimeMillis() + 2_500L
        _state.update {
            it.copy(
                nowPlaying = track.toNowPlaying(isPlaying),
                trackLiked = false,
                lyrics = emptyList(),
                lyricsLoading = true,
                lyricsError = null,
                error = null,
            )
        }
        startProgressTick()
        refreshLikeAndLyrics(track.toNowPlaying(isPlaying))
    }

    private fun refreshLikeAndLyrics(np: NowPlaying) {
        val id = np.id ?: trackIdFromUri(np.uri)
        if (!id.isNullOrBlank() && np.uri != lastLikedUri) {
            lastLikedUri = np.uri
            likeJob?.cancel()
            likeJob = viewModelScope.launch {
                val liked = when (np.source) {
                    MediaSource.SoundCloud -> {
                        val tid = id.toLongOrNull() ?: return@launch
                        if (scLikedIds.isEmpty() && scStore.hasSession) {
                            refreshScLikedIdsFromCache()
                            if (scLikedIds.isEmpty()) {
                                // One head page instead of 25×200 ID crawl
                                runCatching { scApi.getLikedTracks(50) }
                                    .onSuccess { page ->
                                        scLikedIds.addAll(page.items.map { it.id })
                                        likedCache.saveSoundCloud(page.items)
                                    }
                            }
                        }
                        tid in scLikedIds
                    }
                    MediaSource.Spotify -> {
                        val fromCache = likedCache.loadSpotify()?.tracks?.any { it.id == id }
                        when {
                            fromCache == true -> true
                            fromCache == false -> false
                            !spotifyNetworkArmed -> _state.value.trackLiked
                            else -> runCatching { api.isTrackSaved(id) }.getOrDefault(_state.value.trackLiked)
                        }
                    }
                }
                if (_state.value.nowPlaying?.uri == np.uri) {
                    _state.update { it.copy(trackLiked = liked) }
                }
            }
        }
        // Lyrics are Spotify-oriented for now — skip for SoundCloud
        if (np.source == MediaSource.SoundCloud) {
            _state.update {
                it.copy(lyrics = emptyList(), lyricsLoading = false, lyricsError = null)
            }
            return
        }
        val key = "${np.uri}|${np.name}|${np.artists}"
        if (key == lastLyricsKey) return
        lastLyricsKey = key
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _state.update { it.copy(lyrics = emptyList(), lyricsLoading = true, lyricsError = null) }
            val lines = runCatching {
                lyricsClient.fetch(
                    trackName = np.name,
                    artistName = np.artists,
                    albumName = np.album,
                    durationMs = np.durationMs,
                )
            }.getOrElse { e ->
                _state.update {
                    it.copy(lyricsLoading = false, lyricsError = e.message ?: "Lyrics unavailable")
                }
                return@launch
            }
            if (_state.value.nowPlaying?.uri == np.uri) {
                _state.update {
                    it.copy(
                        lyrics = lines,
                        lyricsLoading = false,
                        lyricsError = if (lines.isEmpty()) "No lyrics found" else null,
                    )
                }
            }
        }
    }

    private fun friendlyLoadError(e: Throwable): String {
        val m = (e.message ?: e.toString()).trim()
        val lower = m.lowercase()
        return when {
            lower.contains("empty") ->
                "Empty response from Spotify — the web session may still be waking up. Tap Retry."
            lower.contains("timeout") || lower.contains("timed out") ->
                "Timed out talking to Spotify — check connection and Retry."
            lower.contains("429") || lower.contains("rate limit") ->
                "Spotify rate-limited us — wait a moment and Retry."
            lower.contains("401") || lower.contains("403") || lower.contains("rejected token") ->
                "Session expired — log out and log in again."
            lower.contains("couldn't capture") || lower.contains("web tokens") ->
                "Couldn't get Spotify web tokens yet — wait a second and Retry."
            else -> m.ifBlank { "Couldn't load tracks" }
        }
    }

    private fun trackIdFromUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val id = uri.removePrefix("spotify:track:")
        return id.takeIf { it.isNotBlank() && !it.contains(':') }
    }
}

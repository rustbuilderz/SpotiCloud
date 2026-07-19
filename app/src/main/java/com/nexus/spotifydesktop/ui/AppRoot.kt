package com.nexus.spotifydesktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nexus.spotifydesktop.data.LibraryItem
import com.nexus.spotifydesktop.data.MediaSource
import com.nexus.spotifydesktop.data.NowPlaying
import com.nexus.spotifydesktop.data.TrackItem
import com.nexus.spotifydesktop.ui.theme.SpotiCloudColors
import com.nexus.spotifydesktop.ui.theme.spotiCloudColorScheme
private enum class Tab { Home, Spotify, SoundCloud, Settings }

@Composable
fun AppRoot(
    viewModel: MainViewModel,
    onRefreshSongsWithDevToken: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var inTracks by rememberSaveable { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var playerExpanded by remember { mutableStateOf(false) }

    if (state.showWebLogin) {
        com.nexus.spotifydesktop.auth.WebLoginScreen(
            onSession = viewModel::onWebSessionCaptured,
            onCancel = viewModel::cancelWebLogin,
            onError = viewModel::onAuthError,
        )
        return
    }

    if (state.showScWebLogin) {
        com.nexus.spotifydesktop.soundcloud.SoundCloudWebLogin(
            onCaptured = viewModel::onScWebLoginCaptured,
            onCancel = viewModel::cancelScWebLogin,
        )
        return
    }

    if (state.needsSetup) {
        SetupScreen(
            step = state.setupStep,
            spotifyAuthed = state.authed,
            spotifyUserName = state.userName,
            soundCloudAuthed = state.scAuthed,
            soundCloudUserName = state.scUserName,
            spotifyClientId = state.spotifyClientId,
            scLoading = state.scLoading,
            error = state.error ?: state.scError,
            onAgreeAndStart = viewModel::agreeAndStartSetup,
            onSaveSpotifyClientId = viewModel::saveSpotifyClientId,
            onSpotifyLogin = viewModel::startWebLogin,
            onContinueFromSpotify = viewModel::continueSetupFromSpotify,
            onConnectSoundCloud = { oauth, cid ->
                viewModel.connectSoundCloudManual(oauth, cid)
            },
            onFinish = viewModel::finishSetup,
        )
        return
    }

    if (!state.scCaptchaUrl.isNullOrBlank()) {
        com.nexus.spotifydesktop.soundcloud.SoundCloudCaptchaDialog(
            captchaUrl = state.scCaptchaUrl!!,
            onSolved = viewModel::onScCaptchaSolved,
            onCancel = viewModel::dismissScCaptcha,
        )
    }

    if (!state.scTrackLikeUrl.isNullOrBlank()) {
        com.nexus.spotifydesktop.soundcloud.SoundCloudTrackLikeDialog(
            trackUrl = state.scTrackLikeUrl!!,
            trackName = state.scTrackLikeName,
            onDone = viewModel::onScTrackLikeDone,
            onCancel = viewModel::dismissScTrackLike,
        )
    }

    val themeAccent = when (tab) {
        Tab.SoundCloud -> SpotiCloudColors.SoundCloudOrange
        else -> SpotiCloudColors.SpotifyGreen
    }
    val ambientAccent = when (tab) {
        Tab.SoundCloud -> SpotiCloudColors.SoundCloudOrange
        Tab.Spotify -> SpotiCloudColors.SpotifyGreen
        Tab.Home, Tab.Settings -> Color(0xFF1A1A1A)
    }

    MaterialTheme(colorScheme = spotiCloudColorScheme(themeAccent)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            AmbientBackground(
                accent = ambientAccent,
                playing = state.nowPlaying?.isPlaying == true,
            )

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = when {
                            tab == Tab.Home -> "home"
                            tab == Tab.SoundCloud -> "soundcloud"
                            tab == Tab.Settings -> "settings"
                            inTracks -> "tracks"
                            else -> "spotify"
                        },
                        transitionSpec = {
                            (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                                scaleIn(
                                    initialScale = 0.98f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                )) togetherWith
                                (fadeOut() + scaleOut(targetScale = 0.98f)) using SizeTransform(clip = false)
                        },
                        label = "pane",
                    ) { pane ->
                        when (pane) {
                            "home" -> HomeDashboard(
                                nowPlaying = state.nowPlaying,
                                recents = state.recents,
                                searchQuery = state.searchQuery,
                                searchResults = state.searchResults,
                                searchArtists = state.searchArtists,
                                searchAlbums = state.searchAlbums,
                                searching = state.searching,
                                searchError = state.error.takeIf { state.searchQuery.isNotBlank() },
                                onSearchQuery = viewModel::search,
                                scSearchQuery = state.scSearchQuery,
                                scSearchTracks = state.scTracks.takeIf {
                                    state.scSearchQuery.isNotBlank()
                                }.orEmpty(),
                                scSearchUsers = state.scSearchUsers,
                                scSearchAlbums = state.scSearchAlbums,
                                scSearching = state.scSearching,
                                scSearchError = state.scError.takeIf { state.scSearchQuery.isNotBlank() },
                                onScSearchQuery = viewModel::searchSoundCloud,
                                onPlayTrack = { track, list -> viewModel.playTrack(track, list) },
                                onPlayScTrack = { track, list -> viewModel.playScTrack(track, list) },
                                onPlayRecent = { recent ->
                                    when (recent.source) {
                                        MediaSource.Spotify -> {
                                            val queue = viewModel.homeLikedQueue()
                                            val track = queue.firstOrNull { it.uri == recent.uri }
                                                ?: TrackItem(
                                                    id = recent.id,
                                                    uri = recent.uri,
                                                    name = recent.name,
                                                    artists = recent.artists,
                                                    album = recent.album,
                                                    imageUrl = recent.imageUrl,
                                                    durationMs = 0L,
                                                    primaryArtistId = recent.primaryArtistId,
                                                )
                                            viewModel.playTrack(track, queue.ifEmpty { listOf(track) })
                                        }
                                        MediaSource.SoundCloud -> {
                                            viewModel.homeScTrack(recent.id)?.let { track ->
                                                val queue = viewModel.homeScLikedQueue()
                                                viewModel.playScTrack(
                                                    track,
                                                    queue.ifEmpty { listOf(track) },
                                                )
                                            }
                                        }
                                    }
                                },
                                onOpenSpotify = {
                                    tab = Tab.Spotify
                                    inTracks = false
                                    viewModel.onSpotifyTabOpened()
                                },
                                onOpenSoundCloud = {
                                    tab = Tab.SoundCloud
                                    inTracks = false
                                },
                                onTogglePlay = viewModel::togglePlay,
                                onOpenSpotifyArtist = { id, name ->
                                    tab = Tab.Spotify
                                    viewModel.openSpotifyArtist(id, name)
                                    inTracks = true
                                },
                                onOpenScArtist = { uid, name, permalink ->
                                    tab = Tab.SoundCloud
                                    viewModel.openScArtist(uid, name, permalink)
                                },
                                onOpenSpotifyAlbum = { id, name ->
                                    tab = Tab.Spotify
                                    viewModel.openSpotifyAlbum(id, name)
                                    inTracks = true
                                },
                                onOpenScAlbum = { pl ->
                                    tab = Tab.SoundCloud
                                    viewModel.openScPlaylist(pl)
                                },
                            )

                            "soundcloud" -> SoundCloudPane(
                                authed = state.scAuthed,
                                userName = state.scUserName,
                                section = state.scSection,
                                tracks = state.scTracks,
                                playlists = state.scPlaylists,
                                playlistTitle = state.scPlaylistTitle,
                                searchQuery = state.scSearchQuery,
                                searchUsers = state.scSearchUsers,
                                searchAlbums = state.scSearchAlbums,
                                loading = state.scLoading || state.scSearching,
                                error = state.scError,
                                status = state.scStatus,
                                onSearch = viewModel::searchSoundCloud,
                                onOpenSection = viewModel::openScSection,
                                onOpenPlaylist = viewModel::openScPlaylist,
                                onBack = viewModel::scBack,
                                onConnect = viewModel::startScWebLogin,
                                onOpenTrack = { track ->
                                    viewModel.playScTrack(track, state.scTracks.ifEmpty { listOf(track) })
                                },
                                onOpenArtist = { track ->
                                    viewModel.openScArtist(track.userId, track.artist, track.permalinkUrl)
                                },
                                onOpenUser = { uid, name ->
                                    viewModel.openScArtist(uid, name)
                                },
                            )

                            "settings" -> SettingsPane(
                                userName = state.userName,
                                details = viewModel.systemDetails(),
                                scAuthed = state.scAuthed,
                                scUserName = state.scUserName,
                                scClientId = state.scClientId,
                                scLoading = state.scLoading,
                                scError = state.scError,
                                scLikeMode = state.scLikeMode,
                                consoleLines = state.consoleLines,
                                spotifyClientId = state.spotifyClientId,
                                onLogout = viewModel::logout,
                                onRefresh = viewModel::retryLibrary,
                                onRefreshLikedSongs = viewModel::forceRefreshLikedSongs,
                                onRefreshSongsWithDevToken = onRefreshSongsWithDevToken,
                                onClearCache = viewModel::clearAppCache,
                                onClearConsole = viewModel::clearConsole,
                                onScLogin = viewModel::startScWebLogin,
                                onScDisconnect = viewModel::disconnectSoundCloud,
                                onScRefreshClientId = viewModel::refreshScClientId,
                                onScManualConnect = viewModel::connectSoundCloudManual,
                                onScLikeMode = viewModel::setScLikeMode,
                                onSaveSpotifyClientId = viewModel::saveSpotifyClientId,
                                onResetup = viewModel::startResetup,
                            )

                            "tracks" -> {
                                val selected = state.library.find { it.id == state.selectedId }
                                val isArtist = state.selectedId.startsWith("artist:")
                                val isAlbum = state.selectedId.startsWith("album:")
                                TracksPane(
                                    title = when {
                                        state.detailTitle.isNotBlank() -> state.detailTitle
                                        else -> selected?.name ?: "Tracks"
                                    },
                                    coverUrl = when {
                                        isArtist || isAlbum -> state.tracks.firstOrNull()?.imageUrl
                                            ?: (selected as? LibraryItem.Album)?.imageUrl
                                            ?: (selected as? LibraryItem.Playlist)?.imageUrl
                                        selected is LibraryItem.Playlist -> selected.imageUrl
                                        selected is LibraryItem.Album -> selected.imageUrl
                                        else -> state.tracks.firstOrNull()?.imageUrl
                                    },
                                    isLiked = selected is LibraryItem.Liked,
                                    subtitle = when {
                                        state.detailSubtitle.isNotBlank() -> state.detailSubtitle
                                        selected is LibraryItem.Album -> "Album"
                                        selected is LibraryItem.Liked -> "Playlist"
                                        else -> "Playlist"
                                    },
                                    tracks = state.tracks,
                                    loading = state.tracksLoading,
                                    tracksRefreshing = state.tracksRefreshing,
                                    status = state.status,
                                    playingUri = state.nowPlaying?.uri,
                                    error = state.error,
                                    onBack = { inTracks = false },
                                    onPlayAll = viewModel::playSelected,
                                    onPlayTrack = { track, list -> viewModel.playTrack(track, list) },
                                    onRetry = viewModel::retryTracks,
                                    onArtistClick = { track ->
                                        viewModel.openSpotifyArtist(track.primaryArtistId, track.artists)
                                        inTracks = true
                                        tab = Tab.Spotify
                                    },
                                )
                            }

                            else -> SpotifyLibraryPane(
                                userName = state.userName,
                                library = state.library,
                                libraryLoading = state.libraryLoading,
                                error = state.error,
                                status = state.status,
                                onOpen = { id ->
                                    viewModel.loadSelection(id)
                                    viewModel.search("")
                                    inTracks = true
                                },
                                onRetryLibrary = viewModel::retryLibrary,
                            )
                        }
                    }
                }

                MiniPlayer(
                    nowPlaying = state.nowPlaying,
                    shuffle = state.shuffle,
                    liked = state.trackLiked,
                    volume = state.volume,
                    showVolume = showVolume,
                    status = state.status,
                    error = state.error,
                    onExpand = {
                        if (state.nowPlaying != null) {
                            playerExpanded = true
                        }
                    },
                    onToggleVolume = { showVolume = !showVolume },
                    onToggle = viewModel::togglePlay,
                    onPrev = viewModel::previous,
                    onNext = viewModel::next,
                    onLike = viewModel::toggleLike,
                    onShuffle = viewModel::toggleShuffle,
                    onVolumeDrag = viewModel::onVolumeDrag,
                    onVolumeCommit = viewModel::commitVolume,
                )

                NavigationBar(
                    containerColor = Color(0xCC121212),
                    tonalElevation = 0.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    NavigationBarItem(
                        selected = tab == Tab.Home,
                        onClick = {
                            tab = Tab.Home
                            inTracks = false
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") },
                        colors = navColors(),
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Spotify,
                        onClick = {
                            tab = Tab.Spotify
                            viewModel.onSpotifyTabOpened()
                        },
                        icon = { SpotifyLogo(size = 24.dp) },
                        label = { Text("Spotify") },
                        colors = navColors(),
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SoundCloud,
                        onClick = {
                            tab = Tab.SoundCloud
                            inTracks = false
                        },
                        icon = {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = SpotiCloudColors.SoundCloudOrange,
                            )
                        },
                        label = { Text("SoundCloud") },
                        colors = navColors(),
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Settings,
                        onClick = {
                            tab = Tab.Settings
                            inTracks = false
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        colors = navColors(),
                    )
                }
            }

            ExpandedPlayerOverlay(
                visible = playerExpanded,
                nowPlaying = state.nowPlaying,
                liked = state.trackLiked,
                lyrics = state.lyrics,
                lyricsLoading = state.lyricsLoading,
                lyricsError = state.lyricsError,
                onCollapse = { playerExpanded = false },
                onToggle = viewModel::togglePlay,
                onPrev = viewModel::previous,
                onNext = viewModel::next,
                onLike = viewModel::toggleLike,
            )
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = Color(0xFF1F1F1F),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun LoginScreen(error: String?, onLoginClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("SpotiCloud", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Spotify + SoundCloud in one app. Sign in with Spotify on the web, then connect SoundCloud in Settings.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
        Button(
            onClick = onLoginClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("Log in with Spotify")
        }
    }
}

@Composable
private fun SpotifyLibraryPane(
    userName: String,
    library: List<LibraryItem>,
    libraryLoading: Boolean,
    error: String?,
    status: String,
    onOpen: (String) -> Unit,
    onRetryLibrary: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Spotify", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        libraryLoading -> status.ifBlank { "Loading playlists…" }
                        userName.isNotBlank() -> userName
                        else -> status.ifBlank { "…" }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRetryLibrary, enabled = !libraryLoading) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reload library",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            TextButton(
                onClick = onRetryLibrary,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text("Retry library")
            }
        }

        when {
            libraryLoading && library.size <= 1 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            status.ifBlank { "Loading playlists…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            library.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Library not loaded yet",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "If loading failed, check the error above, then tap Retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetryLibrary) { Text("Retry library") }
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (libraryLoading) {
                        item(key = "library-loading") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    status.ifBlank { "Loading playlists…" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(library, key = { it.id }) { item ->
                        LibraryRow(item = item, onClick = { onOpen(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item) {
            is LibraryItem.Liked -> {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF5038A0)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White)
                }
            }
            is LibraryItem.Playlist -> Cover(item.imageUrl)
            is LibraryItem.Album -> Cover(item.imageUrl)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (item) {
                    is LibraryItem.Liked -> "Playlist"
                    is LibraryItem.Playlist -> "Playlist"
                    is LibraryItem.Album -> "Album"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Cover(url: String?) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun TracksPane(
    title: String,
    coverUrl: String?,
    isLiked: Boolean,
    subtitle: String,
    tracks: List<TrackItem>,
    loading: Boolean,
    tracksRefreshing: Boolean,
    status: String,
    playingUri: String?,
    error: String?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrack: (TrackItem, List<TrackItem>) -> Unit,
    onRetry: () -> Unit,
    onArtistClick: ((TrackItem) -> Unit)? = null,
) {
    var albumQuery by rememberSaveable { mutableStateOf("") }
    val filtered = remember(tracks, albumQuery) {
        val q = albumQuery.trim()
        if (q.isEmpty()) tracks
        else tracks.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.artists.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) tracksContent@{
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(subtitle)
                        append(" · ")
                        if (loading && tracks.isEmpty()) append("Loading…")
                        else if (albumQuery.isNotBlank()) append("${filtered.size} of ${tracks.size}")
                        else append("${tracks.size} songs")
                        if (tracksRefreshing) append(" · Updating…")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        TextField(
            value = albumQuery,
            onValueChange = { albumQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Search in this list") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (albumQuery.isNotBlank()) {
                    IconButton(onClick = { albumQuery = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )

        when {
            loading && tracks.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            status.ifBlank { "Loading songs…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            !loading && tracks.isEmpty() && !error.isNullOrBlank() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (status.isBlank()) "Couldn't load this list" else status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }
            else -> {
            LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(modifier = Modifier.size(160.dp)) {
                            HeroCover(
                                coverUrl = coverUrl,
                                isLiked = isLiked,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            val coverPlayInteraction = remember { MutableInteractionSource() }
                            val coverPressed by coverPlayInteraction.collectIsPressedAsState()
                            val coverPlayScale by animateFloatAsState(
                                targetValue = if (coverPressed) 0.92f else 1f,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                label = "coverPlayScale",
                            )
                            IconButton(
                                onClick = onPlayAll,
                                enabled = tracks.isNotEmpty() && !loading,
                                interactionSource = coverPlayInteraction,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .scale(coverPlayScale)
                                    .size(44.dp)
                                    .shadow(8.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                    }
                }

                error?.let { msg ->
                    item {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                if (filtered.isEmpty() && albumQuery.isNotBlank()) {
                    item {
                        Text(
                            "No matches",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                itemsIndexed(filtered, key = { i, t -> "${t.id}-$i" }) { _, track ->
                    TrackRow(
                        track = track,
                        playing = track.uri == playingUri,
                        onClick = { onPlayTrack(track, if (albumQuery.isBlank()) tracks else filtered) },
                        onArtistClick = onArtistClick,
                    )
                }
            }
            }
        }

    }
}

@Composable
private fun HeroCover(
    coverUrl: String?,
    isLiked: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        isLiked -> {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF5038A0)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(96.dp),
                )
            }
        }
        !coverUrl.isNullOrBlank() -> {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = modifier.clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        else -> {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(80.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchPane(
    query: String,
    results: List<TrackItem>,
    searching: Boolean,
    playingUri: String?,
    error: String?,
    onQuery: (String) -> Unit,
    onPlayTrack: (TrackItem, List<TrackItem>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Text(
            "Search",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp, start = 4.dp),
        )
        TextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What do you want to listen to?") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(8.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }

        when {
            searching -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Searching…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Find songs", style = MaterialTheme.typography.bodyMedium)
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results", style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn {
                itemsIndexed(results, key = { i, t -> "${t.id}-$i" }) { _, track ->
                    TrackRow(
                        track = track,
                        playing = track.uri == playingUri,
                        onClick = { onPlayTrack(track, results) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TrackRow(
    track: TrackItem,
    playing: Boolean,
    onClick: () -> Unit,
    onArtistClick: ((TrackItem) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (playing) Color(0xFF1A1A1A) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artists,
                style = MaterialTheme.typography.bodySmall,
                color = if (onArtistClick != null) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onArtistClick != null && track.artists.isNotBlank()) {
                    Modifier.clickable { onArtistClick(track) }
                } else {
                    Modifier
                },
            )
        }
        Text(formatMs(track.durationMs), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MiniPlayer(
    nowPlaying: NowPlaying?,
    shuffle: Boolean,
    liked: Boolean,
    volume: Float,
    showVolume: Boolean,
    status: String,
    error: String? = null,
    onExpand: () -> Unit,
    onToggleVolume: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLike: () -> Unit,
    onShuffle: () -> Unit,
    onVolumeDrag: (Float) -> Unit,
    onVolumeCommit: () -> Unit,
) {
    val progress = if (nowPlaying != null && nowPlaying.durationMs > 0) {
        (nowPlaying.progressMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xE6282828), Color(0xF0121212)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .clickable(enabled = nowPlaying != null, onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = nowPlaying?.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .shadow(6.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onToggleVolume),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    nowPlaying?.name ?: "Not playing",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    nowPlaying?.artists?.takeIf { it.isNotBlank() } ?: "Choose a song",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onShuffle, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
            }

            val playInteraction = remember { MutableInteractionSource() }
            val pressed by playInteraction.collectIsPressedAsState()
            val playScale by animateFloatAsState(
                targetValue = if (pressed) 0.9f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "playScale",
            )
            IconButton(
                onClick = onToggle,
                interactionSource = playInteraction,
                modifier = Modifier
                    .scale(playScale)
                    .size(56.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    if (nowPlaying?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp),
                )
            }

            IconButton(
                onClick = onLike,
                enabled = nowPlaying != null,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Like",
                    tint = when {
                        nowPlaying == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        liked -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(26.dp),
                )
            }

            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = onToggleVolume, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = "Volume",
                    tint = if (showVolume) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = showVolume,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Vol", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = volume,
                    onValueChange = onVolumeDrag,
                    onValueChangeFinished = onVolumeCommit,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt()
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}

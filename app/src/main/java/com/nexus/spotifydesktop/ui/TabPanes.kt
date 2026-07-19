package com.nexus.spotifydesktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VpnKey
import com.nexus.spotifydesktop.data.ConsoleLine
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nexus.spotifydesktop.data.MediaSource
import com.nexus.spotifydesktop.data.NowPlaying
import com.nexus.spotifydesktop.data.RecentTrack
import com.nexus.spotifydesktop.data.ScBrowseSection
import com.nexus.spotifydesktop.data.ScPlaylist
import com.nexus.spotifydesktop.data.ScTrack
import com.nexus.spotifydesktop.data.SearchFilter
import com.nexus.spotifydesktop.data.TrackItem
import com.nexus.spotifydesktop.soundcloud.ScLikeMode
import com.nexus.spotifydesktop.ui.theme.SpotiCloudColors
@Composable
fun HomeDashboard(
    nowPlaying: NowPlaying?,
    recents: List<RecentTrack>,
    searchQuery: String,
    searchResults: List<TrackItem>,
    searchArtists: List<com.nexus.spotifydesktop.data.SearchArtist> = emptyList(),
    searchAlbums: List<com.nexus.spotifydesktop.data.SearchAlbum> = emptyList(),
    searching: Boolean,
    searchError: String?,
    onSearchQuery: (String) -> Unit,
    scSearchQuery: String = "",
    scSearchTracks: List<ScTrack> = emptyList(),
    scSearchUsers: List<com.nexus.spotifydesktop.data.ScSearchUser> = emptyList(),
    scSearchAlbums: List<ScPlaylist> = emptyList(),
    scSearching: Boolean = false,
    scSearchError: String? = null,
    onScSearchQuery: (String) -> Unit = {},
    onPlayTrack: (TrackItem, List<TrackItem>) -> Unit,
    onPlayScTrack: (ScTrack, List<ScTrack>) -> Unit = { _, _ -> },
    onPlayRecent: (RecentTrack) -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenSoundCloud: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenSpotifyArtist: (artistId: String?, artistName: String) -> Unit = { _, _ -> },
    onOpenScArtist: (userId: Long?, artistName: String, permalinkUrl: String?) -> Unit = { _, _, _ -> },
    onOpenSpotifyAlbum: (albumId: String, albumName: String) -> Unit = { _, _ -> },
    onOpenScAlbum: (ScPlaylist) -> Unit = {},
) {
    var source by rememberSaveable { mutableStateOf(MediaSource.Spotify) }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.All) }
    var sourceMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }

    val activeQuery = if (source == MediaSource.Spotify) searchQuery else scSearchQuery
    val isSearching = if (source == MediaSource.Spotify) searching else scSearching
    val activeError = if (source == MediaSource.Spotify) searchError else scSearchError

    val showSongs = filter == SearchFilter.All || filter == SearchFilter.Song
    val showPeople = filter == SearchFilter.All || filter == SearchFilter.People
    val showAlbums = filter == SearchFilter.All || filter == SearchFilter.Album

    val spSongs = if (showSongs) searchResults else emptyList()
    val spPeople = if (showPeople) searchArtists else emptyList()
    val spAlbums = if (showAlbums) searchAlbums else emptyList()
    val scSongs = if (showSongs) scSearchTracks else emptyList()
    val scPeople = if (showPeople) scSearchUsers else emptyList()
    val scAlbums = if (showAlbums) scSearchAlbums else emptyList()

    val hasAny = if (source == MediaSource.Spotify) {
        spSongs.isNotEmpty() || spPeople.isNotEmpty() || spAlbums.isNotEmpty()
    } else {
        scSongs.isNotEmpty() || scPeople.isNotEmpty() || scAlbums.isNotEmpty()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("SpotiCloud", style = MaterialTheme.typography.titleLarge)
            Text(
                "Search both libraries · jump to a service",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            TextField(
                value = activeQuery,
                onValueChange = { q ->
                    if (source == MediaSource.Spotify) onSearchQuery(q)
                    else onScSearchQuery(q)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (source == MediaSource.Spotify) "Songs, people, albums…"
                        else "SoundCloud songs, people, albums…",
                    )
                },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeQuery.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    if (source == MediaSource.Spotify) onSearchQuery("")
                                    else onScSearchQuery("")
                                },
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { sourceMenu = true }) {
                                if (source == MediaSource.Spotify) {
                                    SpotifyLogo(size = 22.dp)
                                } else {
                                    Icon(
                                        Icons.Default.Cloud,
                                        contentDescription = "Source",
                                        tint = SpotiCloudColors.SoundCloudOrange,
                                    )
                                }
                            }
                            DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Spotify") },
                                    onClick = {
                                        source = MediaSource.Spotify
                                        sourceMenu = false
                                        onScSearchQuery("")
                                    },
                                    leadingIcon = { SpotifyLogo(size = 20.dp) },
                                )
                                DropdownMenuItem(
                                    text = { Text("SoundCloud") },
                                    onClick = {
                                        source = MediaSource.SoundCloud
                                        sourceMenu = false
                                        onSearchQuery("")
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Cloud, null, tint = SpotiCloudColors.SoundCloudOrange)
                                    },
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { filterMenu = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filter",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                                SearchFilter.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (f) {
                                                    SearchFilter.All -> "All"
                                                    SearchFilter.Song -> "Songs"
                                                    SearchFilter.People -> "People"
                                                    SearchFilter.Album -> "Albums"
                                                },
                                            )
                                        },
                                        onClick = {
                                            filter = f
                                            filterMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1A1A1A),
                    unfocusedContainerColor = Color(0xFF1A1A1A),
                    disabledContainerColor = Color(0xFF141414),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Text(
                "Filter: ${
                    when (filter) {
                        SearchFilter.All -> "All"
                        SearchFilter.Song -> "Songs"
                        SearchFilter.People -> "People"
                        SearchFilter.Album -> "Albums"
                    }
                } · Source: ${source.name}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }

        if (activeQuery.isNotBlank()) {
            if (isSearching) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = if (source == MediaSource.SoundCloud) {
                                SpotiCloudColors.SoundCloudOrange
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            } else if (activeError != null) {
                item {
                    Text(
                        activeError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (!hasAny) {
                item {
                    Text("No matches", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (source == MediaSource.Spotify) {
                if (spSongs.isNotEmpty()) {
                    item { Text("Songs", style = MaterialTheme.typography.titleMedium) }
                    itemsIndexed(spSongs, key = { i, t -> "sr-${t.id}-$i" }) { _, track ->
                        TrackRow(
                            track = track,
                            playing = track.uri == nowPlaying?.uri,
                            onClick = { onPlayTrack(track, spSongs) },
                            onArtistClick = {
                                onOpenSpotifyArtist(track.primaryArtistId, track.artists)
                            },
                        )
                    }
                }
                if (spPeople.isNotEmpty()) {
                    item { Text("People", style = MaterialTheme.typography.titleMedium) }
                    items(spPeople, key = { "a-${it.id}" }) { artist ->
                        SearchEntityRow(
                            title = artist.name,
                            subtitle = "Artist",
                            imageUrl = artist.imageUrl,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { onOpenSpotifyArtist(artist.id, artist.name) },
                        )
                    }
                }
                if (spAlbums.isNotEmpty()) {
                    item { Text("Albums", style = MaterialTheme.typography.titleMedium) }
                    items(spAlbums, key = { "al-${it.id}" }) { album ->
                        SearchEntityRow(
                            title = album.name,
                            subtitle = album.artists.ifBlank { "Album" },
                            imageUrl = album.imageUrl,
                            accent = MaterialTheme.colorScheme.secondary,
                            onClick = { onOpenSpotifyAlbum(album.id, album.name) },
                        )
                    }
                }
            } else {
                if (scSongs.isNotEmpty()) {
                    item { Text("Songs", style = MaterialTheme.typography.titleMedium) }
                    items(scSongs, key = { "scs-${it.id}" }) { track ->
                        ScTrackRow(
                            track = track,
                            onClick = { onPlayScTrack(track, scSongs) },
                            onArtistClick = { onOpenScArtist(track.userId, track.artist, track.permalinkUrl) },
                        )
                    }
                }
                if (scPeople.isNotEmpty()) {
                    item { Text("People", style = MaterialTheme.typography.titleMedium) }
                    items(scPeople, key = { "scu-${it.id}" }) { user ->
                        SearchEntityRow(
                            title = user.name,
                            subtitle = "Artist",
                            imageUrl = user.avatarUrl,
                            accent = SpotiCloudColors.SoundCloudOrange,
                            onClick = { onOpenScArtist(user.id, user.name, null) },
                        )
                    }
                }
                if (scAlbums.isNotEmpty()) {
                    item { Text("Albums", style = MaterialTheme.typography.titleMedium) }
                    items(scAlbums, key = { "scal-${it.id}" }) { album ->
                        SearchEntityRow(
                            title = album.title,
                            subtitle = album.artist.ifBlank { "Album" },
                            imageUrl = album.artworkUrl,
                            accent = SpotiCloudColors.SoundCloudOrange,
                            onClick = { onOpenScAlbum(album) },
                        )
                    }
                }
            }
        }

        item {
            Text("Now playing", style = MaterialTheme.typography.titleMedium)
            NowPlayingCard(
                nowPlaying = nowPlaying,
                onTogglePlay = onTogglePlay,
            )
        }

        item {
            Text("Quick open", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickServiceButton(
                    label = "Spotify",
                    accent = SpotiCloudColors.SpotifyGreen,
                    onClick = onOpenSpotify,
                    modifier = Modifier.weight(1f),
                ) {
                    SpotifyLogo(size = 18.dp)
                }
                QuickServiceButton(
                    label = "SoundCloud",
                    accent = SpotiCloudColors.SoundCloudOrange,
                    onClick = onOpenSoundCloud,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        item {
            Text("Recent likes", style = MaterialTheme.typography.titleMedium)
            Text(
                "Up to 15 Spotify + 15 SoundCloud · newest first",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (recents.isEmpty()) {
            item {
                Text(
                    "Waiting for likes…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(recents, key = { "${it.source}-${it.uri}-${it.addedAtMs}" }) { recent ->
                RecentRow(
                    track = recent,
                    playing = recent.uri == nowPlaying?.uri,
                    onClick = { onPlayRecent(recent) },
                    onArtistClick = {
                        when (recent.source) {
                            MediaSource.Spotify ->
                                onOpenSpotifyArtist(recent.primaryArtistId, recent.artists)
                            MediaSource.SoundCloud ->
                                onOpenScArtist(recent.scUserId, recent.artists, recent.permalinkUrl)
                        }
                    },
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun NowPlayingCard(
    nowPlaying: NowPlaying?,
    onTogglePlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF161616))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nowPlaying?.imageUrl != null) {
            AsyncImage(
                model = nowPlaying.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222222)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                nowPlaying?.name ?: "Nothing playing",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                nowPlaying?.artists?.ifBlank { "Pick a track" } ?: "Pick a track",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onTogglePlay,
            enabled = nowPlaying != null,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (nowPlaying?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.Black,
            )
        }
    }
}

@Composable
private fun QuickServiceButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SearchEntityRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF161616))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentRow(
    track: RecentTrack,
    playing: Boolean,
    onClick: () -> Unit,
    onArtistClick: (() -> Unit)? = null,
) {
    val accent = when (track.source) {
        MediaSource.Spotify -> SpotiCloudColors.SpotifyGreen
        MediaSource.SoundCloud -> SpotiCloudColors.SoundCloudOrange
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) Color(0xFF1A1A1A) else Color.Transparent)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.clickable(onClick = onClick)) {
            if (!track.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF222222)),
                    contentAlignment = Alignment.Center,
                ) {
                    when (track.source) {
                        MediaSource.Spotify -> SpotifyLogo(size = 22.dp)
                        MediaSource.SoundCloud -> Icon(
                            Icons.Default.Cloud,
                            null,
                            tint = accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) accent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
            )
            Text(
                "${track.source.name} · ${track.artists}",
                style = MaterialTheme.typography.bodySmall,
                color = if (onArtistClick != null) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = onArtistClick != null,
                        onClick = { onArtistClick?.invoke() },
                    ),
            )
        }
    }
}

@Composable
fun SoundCloudPane(
    authed: Boolean,
    userName: String,
    section: ScBrowseSection,
    tracks: List<ScTrack>,
    playlists: List<ScPlaylist>,
    playlistTitle: String,
    searchQuery: String,
    searchUsers: List<com.nexus.spotifydesktop.data.ScSearchUser> = emptyList(),
    searchAlbums: List<ScPlaylist> = emptyList(),
    loading: Boolean,
    error: String?,
    status: String,
    onSearch: (String) -> Unit,
    onOpenSection: (ScBrowseSection) -> Unit,
    onOpenPlaylist: (ScPlaylist) -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onOpenTrack: (ScTrack) -> Unit,
    onOpenArtist: (ScTrack) -> Unit = {},
    onOpenUser: (Long, String) -> Unit = { _, _ -> },
) {
    when (section) {
        ScBrowseSection.Hub -> SoundCloudHub(
            authed = authed,
            userName = userName,
            searchQuery = searchQuery,
            loading = loading,
            error = error,
            onSearch = onSearch,
            onOpenSection = onOpenSection,
            onConnect = onConnect,
        )
        ScBrowseSection.Search -> SoundCloudSearchResults(
            query = searchQuery,
            tracks = tracks,
            users = searchUsers,
            albums = searchAlbums,
            loading = loading,
            error = error,
            status = status,
            onBack = onBack,
            onSearch = onSearch,
            onOpenTrack = onOpenTrack,
            onOpenArtist = onOpenArtist,
            onOpenUser = onOpenUser,
            onOpenAlbum = onOpenPlaylist,
        )
        ScBrowseSection.Feed,
        ScBrowseSection.Liked,
        ScBrowseSection.PlaylistTracks,
        ScBrowseSection.ArtistTracks,
        -> SoundCloudTrackList(
            title = when (section) {
                ScBrowseSection.Feed -> "Stream"
                ScBrowseSection.Liked -> "Liked tracks"
                ScBrowseSection.ArtistTracks -> playlistTitle.ifBlank { "Artist" }
                else -> playlistTitle.ifBlank { "Tracks" }
            },
            subtitle = status,
            tracks = tracks,
            loading = loading,
            error = error,
            onBack = onBack,
            onOpenTrack = onOpenTrack,
            onOpenArtist = onOpenArtist,
        )
        ScBrowseSection.Playlists -> SoundCloudPlaylistList(
            playlists = playlists,
            loading = loading,
            error = error,
            status = status,
            onBack = onBack,
            onOpen = onOpenPlaylist,
        )
    }
}

@Composable
private fun SoundCloudHub(
    authed: Boolean,
    userName: String,
    searchQuery: String,
    loading: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onOpenSection: (ScBrowseSection) -> Unit,
    onConnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("SoundCloud", style = MaterialTheme.typography.titleLarge)
            Text(
                if (authed) {
                    if (userName.isBlank()) "Connected" else "Signed in as $userName"
                } else {
                    "Connect in Settings or tap below"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            TextField(
                value = searchQuery,
                onValueChange = onSearch,
                enabled = authed,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Songs, people, albums…") },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = SpotiCloudColors.SoundCloudOrange.copy(alpha = 0.7f))
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearch("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (loading && searchQuery.isNotBlank()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = SpotiCloudColors.SoundCloudOrange,
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1A1410),
                    unfocusedContainerColor = Color(0xFF1A1410),
                    disabledContainerColor = Color(0xFF1A1410),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }

        if (!authed) {
            item {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotiCloudColors.SoundCloudOrange,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Default.Cloud, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect SoundCloud")
                }
            }
        }

        if (!error.isNullOrBlank()) {
            item {
                Text(error, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            ScListCard(
                title = "Liked tracks",
                subtitle = "Your SoundCloud likes",
                accent = SpotiCloudColors.SoundCloudOrange,
                enabled = authed,
                onClick = { onOpenSection(ScBrowseSection.Liked) },
            )
        }
        item {
            ScListCard(
                title = "Playlists",
                subtitle = "Sets & collections",
                accent = SpotiCloudColors.SoundCloudOrange,
                enabled = authed,
                onClick = { onOpenSection(ScBrowseSection.Playlists) },
            )
        }
        item {
            ScListCard(
                title = "Stream / Discover",
                subtitle = "Your SoundCloud feed",
                accent = SpotiCloudColors.SoundCloudOrange,
                enabled = authed,
                onClick = { onOpenSection(ScBrowseSection.Feed) },
            )
        }
        item {
            Text(
                "Tap a track to play in-app · mini player works for SoundCloud too.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SoundCloudSearchResults(
    query: String,
    tracks: List<ScTrack>,
    users: List<com.nexus.spotifydesktop.data.ScSearchUser>,
    albums: List<ScPlaylist>,
    loading: Boolean,
    error: String?,
    status: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenTrack: (ScTrack) -> Unit,
    onOpenArtist: (ScTrack) -> Unit,
    onOpenUser: (Long, String) -> Unit,
    onOpenAlbum: (ScPlaylist) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    onSearch("")
                    onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SpotiCloudColors.SoundCloudOrange,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Search", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (query.isBlank()) status else "“$query” · $status",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = SpotiCloudColors.SoundCloudOrange,
                    )
                }
            }
        }
        item {
            TextField(
                value = query,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Songs, people, albums…") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = SpotiCloudColors.SoundCloudOrange.copy(alpha = 0.7f))
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { onSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1A1410),
                    unfocusedContainerColor = Color(0xFF1A1410),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        if (!error.isNullOrBlank()) {
            item {
                Text(error, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!loading && tracks.isEmpty() && users.isEmpty() && albums.isEmpty() && query.isNotBlank()) {
            item { Text("No matches", style = MaterialTheme.typography.bodyMedium) }
        }
        if (tracks.isNotEmpty()) {
            item { Text("Songs", style = MaterialTheme.typography.titleMedium) }
            items(tracks, key = { "t-${it.id}" }) { track ->
                ScTrackRow(
                    track = track,
                    onClick = { onOpenTrack(track) },
                    onArtistClick = { onOpenArtist(track) },
                )
            }
        }
        if (users.isNotEmpty()) {
            item { Text("People", style = MaterialTheme.typography.titleMedium) }
            items(users, key = { "u-${it.id}" }) { user ->
                SearchEntityRow(
                    title = user.name,
                    subtitle = "Artist",
                    imageUrl = user.avatarUrl,
                    accent = SpotiCloudColors.SoundCloudOrange,
                    onClick = { onOpenUser(user.id, user.name) },
                )
            }
        }
        if (albums.isNotEmpty()) {
            item { Text("Albums", style = MaterialTheme.typography.titleMedium) }
            items(albums, key = { "al-${it.id}" }) { album ->
                SearchEntityRow(
                    title = album.title,
                    subtitle = album.artist.ifBlank { "Album" },
                    imageUrl = album.artworkUrl,
                    accent = SpotiCloudColors.SoundCloudOrange,
                    onClick = { onOpenAlbum(album) },
                )
            }
        }
    }
}

@Composable
private fun SoundCloudTrackList(
    title: String,
    subtitle: String,
    tracks: List<ScTrack>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onOpenTrack: (ScTrack) -> Unit,
    onOpenArtist: (ScTrack) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SpotiCloudColors.SoundCloudOrange,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = SpotiCloudColors.SoundCloudOrange,
                    )
                }
            }
        }
        if (!error.isNullOrBlank()) {
            item {
                Text(error, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!loading && tracks.isEmpty()) {
            item {
                Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(tracks, key = { it.id }) { track ->
            ScTrackRow(
                track = track,
                onClick = { onOpenTrack(track) },
                onArtistClick = { onOpenArtist(track) },
            )
        }
    }
}

@Composable
private fun SoundCloudPlaylistList(
    playlists: List<ScPlaylist>,
    loading: Boolean,
    error: String?,
    status: String,
    onBack: () -> Unit,
    onOpen: (ScPlaylist) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SpotiCloudColors.SoundCloudOrange,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Playlists", style = MaterialTheme.typography.titleLarge)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = SpotiCloudColors.SoundCloudOrange,
                    )
                }
            }
        }
        if (!error.isNullOrBlank()) {
            item {
                Text(error, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(playlists, key = { it.id }) { pl ->
            ScListCard(
                title = pl.title,
                subtitle = "${pl.artist} · ${pl.trackCount} tracks",
                accent = SpotiCloudColors.SoundCloudOrange,
                imageUrl = pl.artworkUrl,
                enabled = true,
                onClick = { onOpen(pl) },
            )
        }
    }
}

@Composable
private fun ScTrackRow(
    track: ScTrack,
    onClick: () -> Unit,
    onArtistClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF16120E))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SpotiCloudColors.SoundCloudOrange.copy(alpha = 0.15f))
                .clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = if (onArtistClick != null) {
                    SpotiCloudColors.SoundCloudOrange.copy(alpha = 0.95f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = onArtistClick != null && track.artist.isNotBlank(),
                        onClick = { onArtistClick?.invoke() },
                    ),
            )
            track.likedAtLabel()?.let { liked ->
                Text(
                    liked,
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotiCloudColors.SoundCloudOrange.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.clickable(onClick = onClick),
                )
            }
        }
    }
}

@Composable
private fun ScListCard(
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean = true,
    imageUrl: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16120E))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.25f else 0.1f), RoundedCornerShape(12.dp))
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick)
                else Modifier,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Cloud, null, tint = accent.copy(alpha = if (enabled) 1f else 0.4f))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

@Composable
fun SettingsPane(
    userName: String,
    details: List<Pair<String, String>>,
    scAuthed: Boolean,
    scUserName: String,
    scClientId: String,
    scLoading: Boolean,
    scError: String?,
    scLikeMode: ScLikeMode,
    consoleLines: List<ConsoleLine>,
    spotifyClientId: String,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshLikedSongs: () -> Unit,
    onRefreshSongsWithDevToken: () -> Unit,
    onClearCache: () -> Unit,
    onClearConsole: () -> Unit,
    onScLogin: () -> Unit,
    onScDisconnect: () -> Unit,
    onScRefreshClientId: () -> Unit,
    onScManualConnect: (oauth: String, clientId: String?) -> Unit,
    onScLikeMode: (ScLikeMode) -> Unit,
    onSaveSpotifyClientId: (String) -> Unit,
    onResetup: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var showScManual by remember { mutableStateOf(false) }
    var oauthDraft by rememberSaveable { mutableStateOf("") }
    var clientIdDraft by rememberSaveable { mutableStateOf("") }
    var spotifyCidDraft by rememberSaveable(spotifyClientId) { mutableStateOf(spotifyClientId) }

    if (showConsole) {
        ConsolePane(
            lines = consoleLines,
            onBack = { showConsole = false },
            onClear = onClearConsole,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Text(
                if (userName.isBlank()) "SpotiCloud · account & app" else "SpotiCloud · $userName",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Text("Spotify", style = MaterialTheme.typography.titleMedium)
        }
        item {
            Text(
                "Client ID is stored on this device only (not in the APK).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = spotifyCidDraft,
                onValueChange = { spotifyCidDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Spotify Client ID (optional)") },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onSaveSpotifyClientId(spotifyCidDraft) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Client ID")
            }
        }
        item {
            SettingsAction(
                title = "Refresh library",
                subtitle = "Reload Spotify playlists & albums",
                icon = Icons.Default.Refresh,
                onClick = onRefresh,
            )
        }
        item {
            SettingsAction(
                title = "Refresh Liked Songs",
                subtitle = "Pathfinder (api-partner) — same as the website",
                icon = Icons.Default.Favorite,
                onClick = onRefreshLikedSongs,
            )
        }
        item {
            SettingsAction(
                title = "Refresh songs with Developer token",
                subtitle = "Optional test · official OAuth /me/tracks",
                icon = Icons.Default.VpnKey,
                onClick = onRefreshSongsWithDevToken,
            )
        }
        item {
            SettingsAction(
                title = "Clear cache",
                subtitle = "Playback + liked + library caches (keeps login)",
                icon = Icons.Default.Delete,
                onClick = onClearCache,
            )
        }
        item {
            SettingsAction(
                title = "Console",
                subtitle = if (consoleLines.isEmpty()) {
                    "Live log · rate limits, errors, loads"
                } else {
                    "${consoleLines.size} lines · tap to open"
                },
                icon = Icons.Default.Terminal,
                onClick = { showConsole = true },
            )
        }
        item {
            SettingsAction(
                title = "System details",
                subtitle = if (showDetails) "Tap to hide" else "App, device, session",
                icon = Icons.Default.Info,
                onClick = { showDetails = !showDetails },
            )
        }

        if (showDetails) {
            items(details, key = { it.first }) { (k, v) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF161616))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text("SoundCloud", style = MaterialTheme.typography.titleMedium)
            Text(
                if (scAuthed) {
                    "Connected as ${scUserName.ifBlank { "user" }}"
                } else {
                    "Login preferred · or paste OAuth from DevTools"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (!scError.isNullOrBlank()) {
            item {
                Text(scError, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Text("Like mode", style = MaterialTheme.typography.titleSmall)
            Text(
                "How the heart likes SoundCloud tracks",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            SettingsAction(
                title = if (scLikeMode == ScLikeMode.WebView) "✓ Open track (safer)" else "Open track (safer)",
                subtitle = "In-app page — you tap Like yourself",
                icon = Icons.Default.Cloud,
                onClick = { onScLikeMode(ScLikeMode.WebView) },
            )
        }
        item {
            SettingsAction(
                title = if (scLikeMode == ScLikeMode.Api) "✓ API like" else "API like",
                subtitle = "App likes for you — can trigger CAPTCHA / blocks",
                icon = Icons.Default.Favorite,
                onClick = { onScLikeMode(ScLikeMode.Api) },
            )
        }

        if (scAuthed) {
            item {
                SettingsAction(
                    title = "Disconnect SoundCloud",
                    subtitle = "Clears OAuth token on this device",
                    icon = Icons.AutoMirrored.Filled.Logout,
                    onClick = onScDisconnect,
                )
            }
        } else {
            item {
                Text(
                    "No SoundCloud developer app needed — paste your session credentials.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { SoundCloudCredsInstructions() }
            item {
                SettingsAction(
                    title = if (showScManual) "Hide credential fields" else "Enter Client ID + OAuth",
                    subtitle = "Both required",
                    icon = Icons.Default.Link,
                    onClick = { showScManual = !showScManual },
                )
            }
        }

        item {
            SettingsAction(
                title = "Refresh client_id",
                subtitle = if (scClientId.isBlank()) {
                    "Auto-scrape from soundcloud.com"
                } else {
                    "Current: ${scClientId.take(10)}…"
                },
                icon = Icons.Default.Refresh,
                onClick = onScRefreshClientId,
            )
        }

        if (showScManual && !scAuthed) {
            item {
                TextField(
                    value = clientIdDraft.ifBlank { scClientId },
                    onValueChange = { clientIdDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Client ID") },
                    placeholder = { Text("IRnK0myxxLJdwXXjybXQo71m…") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1A1410),
                        unfocusedContainerColor = Color(0xFF1A1410),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Text(
                    "From the request URL: value after client_id=",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                TextField(
                    value = oauthDraft,
                    onValueChange = { oauthDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OAuth Token") },
                    placeholder = { Text("2-123456-7890123-xxxxxxxx") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1A1410),
                        unfocusedContainerColor = Color(0xFF1A1410),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Text(
                    "Paste with or without the “OAuth ” prefix — both work.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Button(
                    onClick = {
                        onScManualConnect(
                            oauthDraft,
                            clientIdDraft.ifBlank { scClientId },
                        )
                    },
                    enabled = !scLoading &&
                        oauthDraft.isNotBlank() &&
                        clientIdDraft.ifBlank { scClientId }.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotiCloudColors.SoundCloudOrange,
                        contentColor = Color.White,
                    ),
                ) {
                    if (scLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("Connect")
                    }
                }
            }
        }

        item {
            SettingsAction(
                title = "Re-setup",
                subtitle = "Sign out of Spotify + SoundCloud and run setup again",
                icon = Icons.Default.Refresh,
                onClick = onResetup,
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A1515),
                    contentColor = Color(0xFFFF8A80),
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text("Log out of Spotify")
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF161616))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

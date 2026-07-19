package com.nexus.spotifydesktop.data

enum class MediaSource { Spotify, SoundCloud }

enum class SearchFilter {
    All,
    Song,
    People,
    Album,
}

data class SearchArtist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
)

data class SearchAlbum(
    val id: String,
    val name: String,
    val artists: String,
    val imageUrl: String? = null,
    val uri: String = "",
)

data class SpotifySearchResults(
    val songs: List<TrackItem> = emptyList(),
    val artists: List<SearchArtist> = emptyList(),
    val albums: List<SearchAlbum> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
    val totalCount: Int get() = songs.size + artists.size + albums.size
}

data class ScSearchUser(
    val id: Long,
    val name: String,
    val avatarUrl: String? = null,
)

data class RecentTrack(
    val source: MediaSource,
    val id: String,
    val uri: String,
    val name: String,
    val artists: String,
    val album: String = "",
    val imageUrl: String? = null,
    val addedAtMs: Long = System.currentTimeMillis(),
    val primaryArtistId: String? = null,
    val scUserId: Long? = null,
    val permalinkUrl: String? = null,
)

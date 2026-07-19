package com.nexus.spotifydesktop.data

data class TrackItem(
    val id: String,
    val uri: String,
    val name: String,
    val artists: String,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long,
    /** When saved to Liked Songs (ms), if known. */
    val likedAtMs: Long? = null,
    /** Primary (first) artist Spotify id — for opening top tracks. */
    val primaryArtistId: String? = null,
)

sealed class LibraryItem {
    abstract val id: String
    abstract val name: String

    data object Liked : LibraryItem() {
        override val id = "liked"
        override val name = "Liked Songs"
    }

    data class Playlist(
        override val id: String,
        override val name: String,
        val uri: String,
        val imageUrl: String?,
    ) : LibraryItem()

    data class Album(
        override val id: String,
        override val name: String,
        val uri: String,
        val imageUrl: String?,
    ) : LibraryItem()
}

data class NowPlaying(
    val id: String? = null,
    val name: String,
    val artists: String,
    val album: String = "",
    val imageUrl: String?,
    val uri: String?,
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    val shuffle: Boolean = false,
    val source: MediaSource = MediaSource.Spotify,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

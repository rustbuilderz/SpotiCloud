package com.nexus.spotifydesktop.data

data class ScTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val permalinkUrl: String,
    val durationMs: Long,
    val likesCount: Int = 0,
    val playbackCount: Int = 0,
    val likedAtIso: String? = null,
    val trackAuthorization: String? = null,
    val progressiveTranscodingUrl: String? = null,
    val hlsTranscodingUrl: String? = null,
    val userId: Long? = null,
) {
    val uri: String get() = "soundcloud:tracks:$id"

    fun likedAtMs(): Long? = parseIsoToEpochMs(likedAtIso)

    fun likedAtLabel(): String? {
        val ms = likedAtMs() ?: return likedAtIso?.take(10)?.let { "Liked $it" }
        return runCatching {
            val zoned = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
            "Liked ${zoned.format(fmt)}"
        }.getOrNull()
    }
}

data class ScPlaylist(
    val id: Long,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val permalinkUrl: String,
    val trackCount: Int,
    val durationMs: Long = 0L,
)

data class ScPage<T>(
    val items: List<T>,
    val nextHref: String? = null,
)

enum class ScBrowseSection {
    Hub,
    Feed,
    Liked,
    Playlists,
    PlaylistTracks,
    ArtistTracks,
    Search,
}

fun scArtworkLarge(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return url
        .replace("-large", "-t500x500")
        .replace("-small", "-t500x500")
        .replace("-tiny", "-t500x500")
        .replace("-badge", "-t500x500")
}

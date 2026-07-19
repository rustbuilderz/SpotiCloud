package com.nexus.spotifydesktop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disk cache for Liked lists — instant UI + head-compare to skip full API pagination.
 */
class LikedLibraryCache(context: Context) {
    private val dir = File(context.filesDir, "liked_cache").also { it.mkdirs() }
    private val spotifyFile = File(dir, "spotify_liked.json")
    private val soundCloudFile = File(dir, "soundcloud_liked.json")

    data class SpotifySnapshot(
        val tracks: List<TrackItem>,
        val total: Int?,
        val savedAtMs: Long,
    )

    data class SoundCloudSnapshot(
        val tracks: List<ScTrack>,
        val savedAtMs: Long,
    )

    fun loadSpotify(): SpotifySnapshot? = runCatching {
        if (!spotifyFile.isFile) return null
        val root = JSONObject(spotifyFile.readText())
        val arr = root.optJSONArray("tracks") ?: return null
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    TrackItem(
                        id = o.optString("id"),
                        uri = o.optString("uri"),
                        name = o.optString("name"),
                        artists = o.optString("artists"),
                        album = o.optString("album"),
                        imageUrl = o.optString("imageUrl").takeIf { it.isNotBlank() },
                        durationMs = o.optLong("durationMs"),
                        likedAtMs = o.optLong("likedAtMs").takeIf { o.has("likedAtMs") && it > 0L },
                        primaryArtistId = o.optString("primaryArtistId").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        if (tracks.isEmpty()) return null
        SpotifySnapshot(
            tracks = tracks,
            total = if (root.has("total")) root.optInt("total") else null,
            savedAtMs = root.optLong("savedAtMs"),
        )
    }.getOrNull()

    fun saveSpotify(tracks: List<TrackItem>, total: Int?) {
        if (tracks.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("uri", t.uri)
                        .put("name", t.name)
                        .put("artists", t.artists)
                        .put("album", t.album)
                        .put("imageUrl", t.imageUrl ?: "")
                        .put("durationMs", t.durationMs)
                        .put("likedAtMs", t.likedAtMs ?: 0L)
                        .put("primaryArtistId", t.primaryArtistId ?: ""),
                )
            }
            val root = JSONObject()
                .put("tracks", arr)
                .put("savedAtMs", System.currentTimeMillis())
            if (total != null) root.put("total", total)
            spotifyFile.writeText(root.toString())
        }
    }

    fun loadSoundCloud(): SoundCloudSnapshot? = runCatching {
        if (!soundCloudFile.isFile) return null
        val root = JSONObject(soundCloudFile.readText())
        val arr = root.optJSONArray("tracks") ?: return null
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id")
                if (id == 0L) continue
                add(
                    ScTrack(
                        id = id,
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        artworkUrl = o.optString("artworkUrl").takeIf { it.isNotBlank() },
                        permalinkUrl = o.optString("permalinkUrl"),
                        durationMs = o.optLong("durationMs"),
                        likesCount = o.optInt("likesCount"),
                        playbackCount = o.optInt("playbackCount"),
                        likedAtIso = o.optString("likedAtIso").takeIf { it.isNotBlank() },
                        trackAuthorization = o.optString("trackAuthorization").takeIf { it.isNotBlank() },
                        progressiveTranscodingUrl =
                            o.optString("progressive").takeIf { it.isNotBlank() },
                        hlsTranscodingUrl = o.optString("hls").takeIf { it.isNotBlank() },
                        userId = o.optLong("userId").takeIf { o.has("userId") && it != 0L },
                    ),
                )
            }
        }
        if (tracks.isEmpty()) return null
        SoundCloudSnapshot(tracks = tracks, savedAtMs = root.optLong("savedAtMs"))
    }.getOrNull()

    fun saveSoundCloud(tracks: List<ScTrack>) {
        if (tracks.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("title", t.title)
                        .put("artist", t.artist)
                        .put("artworkUrl", t.artworkUrl ?: "")
                        .put("permalinkUrl", t.permalinkUrl)
                        .put("durationMs", t.durationMs)
                        .put("likesCount", t.likesCount)
                        .put("playbackCount", t.playbackCount)
                        .put("likedAtIso", t.likedAtIso ?: "")
                        .put("trackAuthorization", t.trackAuthorization ?: "")
                        .put("progressive", t.progressiveTranscodingUrl ?: "")
                        .put("hls", t.hlsTranscodingUrl ?: "")
                        .put("userId", t.userId ?: 0L),
                )
            }
            soundCloudFile.writeText(
                JSONObject()
                    .put("tracks", arr)
                    .put("savedAtMs", System.currentTimeMillis())
                    .toString(),
            )
        }
    }

    fun clear() {
        runCatching { spotifyFile.delete() }
        runCatching { soundCloudFile.delete() }
    }

    fun clearSoundCloud() {
        runCatching { soundCloudFile.delete() }
    }

    fun clearSpotify() {
        runCatching { spotifyFile.delete() }
    }

    companion object {
        /**
         * Walk head of remote vs cache: same id at each index → unchanged.
         * First mismatch → list changed (new like, unlike, reorder).
         */
        fun <T> headMatches(
            cached: List<T>,
            remoteHead: List<T>,
            idOf: (T) -> String,
        ): Boolean {
            if (remoteHead.isEmpty()) return cached.isEmpty()
            if (cached.isEmpty()) return false
            val n = minOf(cached.size, remoteHead.size)
            for (i in 0 until n) {
                if (idOf(cached[i]) != idOf(remoteHead[i])) return false
            }
            return true
        }
    }
}

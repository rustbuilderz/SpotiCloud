package com.nexus.spotifydesktop.player

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.nexus.spotifydesktop.data.MediaSource
import com.nexus.spotifydesktop.data.NowPlaying

/**
 * Last known track + position so the mini-player can resume after relaunch
 * without opening Spotify. Live state from App Remote overwrites this when available.
 */
class PlaybackCache(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("playback_cache", Context.MODE_PRIVATE)

    fun save(np: NowPlaying?) {
        if (np == null || np.uri.isNullOrBlank() || np.name.isBlank()) {
            clear()
            return
        }
        prefs.edit {
            putString(KEY_URI, np.uri)
            putString(KEY_ID, np.id)
            putString(KEY_NAME, np.name)
            putString(KEY_ARTISTS, np.artists)
            putString(KEY_ALBUM, np.album)
            putString(KEY_IMAGE, np.imageUrl)
            putLong(KEY_PROGRESS, np.progressMs.coerceAtLeast(0L))
            putLong(KEY_DURATION, np.durationMs.coerceAtLeast(0L))
            putBoolean(KEY_SHUFFLE, np.shuffle)
            // Always store as paused snapshot — we don't know if Spotify kept playing
            putBoolean(KEY_PLAYING, false)
            putString(KEY_SOURCE, np.source.name)
            putLong(KEY_SAVED_AT, System.currentTimeMillis())
        }
    }

    fun load(): NowPlaying? {
        val uri = prefs.getString(KEY_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val source = runCatching {
            MediaSource.valueOf(prefs.getString(KEY_SOURCE, MediaSource.Spotify.name)!!)
        }.getOrDefault(MediaSource.Spotify)
        return NowPlaying(
            id = prefs.getString(KEY_ID, null),
            name = name,
            artists = prefs.getString(KEY_ARTISTS, "").orEmpty(),
            album = prefs.getString(KEY_ALBUM, "").orEmpty(),
            imageUrl = prefs.getString(KEY_IMAGE, null),
            uri = uri,
            isPlaying = false,
            progressMs = prefs.getLong(KEY_PROGRESS, 0L),
            durationMs = prefs.getLong(KEY_DURATION, 0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            source = source,
        )
    }

    fun clear() = prefs.edit { clear() }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_ARTISTS = "artists"
        private const val KEY_ALBUM = "album"
        private const val KEY_IMAGE = "image"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_DURATION = "duration"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_PLAYING = "playing"
        private const val KEY_SOURCE = "source"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
